package io.outbox.jdbc;

import io.outbox.EventEnvelope;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.JdbcOutboxStores;
import io.outbox.model.EventStatus;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.OutboxStore;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract base for outbox store integration tests against real databases.
 * Subclasses provide the DataSource and store instance.
 */
abstract class AbstractOutboxStoreIntegrationTest {

    abstract DataSource dataSource();

    abstract AbstractJdbcOutboxStore store();

    /** This store's type on another table. */
    abstract AbstractJdbcOutboxStore storeFor(String table);

    /** This store's type stamping {@code owner} on the rows it writes. */
    abstract AbstractJdbcOutboxStore stampingStore(String owner);

    /** Makes a statement on {@code conn} give up after about a second of waiting for a row lock. */
    abstract void shortLockWait(Connection conn) throws Exception;

    /** The database's timestamp column type with {@code digits} fractional-second digits. */
    abstract String timestampType(int digits);

    // ── Schema verification: each driver reports timestamp precision its own way ──

    private void createTimestampTable(String table, int createdDigits, int lockedDigits) throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            conn.createStatement().execute("DROP TABLE IF EXISTS " + table);
            conn.createStatement().execute("CREATE TABLE " + table + " (event_id VARCHAR(36) PRIMARY KEY, "
                    + "created_at " + timestampType(createdDigits) + " NOT NULL, "
                    + "locked_at " + timestampType(lockedDigits) + " NULL)");
        }
    }

    private void verify(AbstractJdbcOutboxStore store) throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            store.verifySchema(conn);
        }
    }

    @Test
    void verifySchemaAcceptsTheShippedSchemaAndMillisecondColumns() throws Exception {
        verify(store());
        createTimestampTable("verify_ms", 3, 3);
        verify(storeFor("verify_ms"));
    }

    @Test
    void verifySchemaRefusesAWholeSecondLockedAt() throws Exception {
        createTimestampTable("verify_coarse", 0, 0);
        var e = assertThrows(IllegalStateException.class, () -> verify(storeFor("verify_coarse")));
        assertTrue(e.getMessage().contains("locked_at"), e.getMessage());
    }

    @Test
    void verifySchemaRefusesCreatedAtAndLockedAtAtDifferentPrecisions() throws Exception {
        createTimestampTable("verify_mixed", 6, 3);
        var e = assertThrows(IllegalStateException.class, () -> verify(storeFor("verify_mixed")));
        assertTrue(e.getMessage().contains("differ"), e.getMessage());
    }

    @Test
    void insertNewAndPollPending() throws Exception {
        EventEnvelope envelope = EventEnvelope.builder("TestEvent")
                .payloadJson("{\"key\":\"value\"}")
                .headers(Map.of("h1", "v1"))
                .build();

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            List<OutboxEvent> events = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertEquals(1, events.size());
            OutboxEvent event = events.get(0);
            assertEquals(envelope.eventId(), event.eventId());
            assertEquals("TestEvent", event.eventType());
            assertTrue(event.payloadJson().contains("\"key\""),
                    "Payload should contain key: " + event.payloadJson());
            assertTrue(event.payloadJson().contains("\"value\""),
                    "Payload should contain value: " + event.payloadJson());
        }
    }

    @Test
    void insertBatchWithMultipleEvents() throws Exception {
        List<EventEnvelope> envelopes = List.of(
                EventEnvelope.ofJson("BatchA", "{\"n\":1}"),
                EventEnvelope.ofJson("BatchB", "{\"n\":2}"),
                EventEnvelope.ofJson("BatchC", "{\"n\":3}")
        );

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertBatch(conn, envelopes);

            List<OutboxEvent> events = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertEquals(3, events.size());
        }
    }

    @Test
    void markDoneUpdatesStatus() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("DoneEvent", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            int updated = store().markDone(conn, envelope.eventId());
            assertEquals(1, updated);

            List<OutboxEvent> pending = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertTrue(pending.isEmpty());
        }
    }

    @Test
    void markRetryIncrementsAttempts() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("RetryEvent", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            Instant nextAt = Instant.now().plusSeconds(60);
            int updated = store().markRetry(conn, envelope.eventId(), nextAt, "test error");
            assertEquals(1, updated);

            // Event should not appear in poll (available_at is in the future)
            List<OutboxEvent> pending = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertTrue(pending.isEmpty());

            // Mark retry again with immediate nextAt
            Instant now = Instant.now();
            store().markRetry(conn, envelope.eventId(), now, "retry 2");
            pending = store().pollPending(conn, now.plusSeconds(1), Duration.ZERO, 10);
            assertEquals(1, pending.size());
            assertEquals(2, pending.get(0).attempts());
        }
    }

    @Test
    void markDeadUpdatesStatus() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("DeadEvent", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            int updated = store().markDead(conn, envelope.eventId(), "permanent failure");
            assertEquals(1, updated);

            List<OutboxEvent> pending = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertTrue(pending.isEmpty());
        }
    }

    @Test
    void pollPendingExcludesTerminal() throws Exception {
        EventEnvelope done = EventEnvelope.ofJson("DoneE", "{}");
        EventEnvelope dead = EventEnvelope.ofJson("DeadE", "{}");
        EventEnvelope pending = EventEnvelope.ofJson("PendingE", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, done);
            store().insertNew(conn, dead);
            store().insertNew(conn, pending);
            store().markDone(conn, done.eventId());
            store().markDead(conn, dead.eventId(), "dead");

            List<OutboxEvent> events = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertEquals(1, events.size());
            assertEquals(pending.eventId(), events.get(0).eventId());
        }
    }

    @Test
    void pollPendingRespectsLimit() throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            for (int i = 0; i < 5; i++) {
                store().insertNew(conn, EventEnvelope.ofJson("LimitEvent", "{\"i\":" + i + "}"));
            }

            List<OutboxEvent> events = store().pollPending(conn, Instant.now(), Duration.ZERO, 3);
            assertEquals(3, events.size());
        }
    }

    @Test
    void claimPendingLocksRows() throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            for (int i = 0; i < 3; i++) {
                store().insertNew(conn, EventEnvelope.ofJson("ClaimEvent", "{\"i\":" + i + "}"));
            }

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);
            List<OutboxEvent> claimed = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 2);
            assertEquals(2, claimed.size());
        }
    }

    @Test
    void claimPendingSkipsAlreadyLocked() throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            for (int i = 0; i < 3; i++) {
                store().insertNew(conn, EventEnvelope.ofJson("SkipEvent", "{\"i\":" + i + "}"));
            }

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);

            // First claim takes 2
            List<OutboxEvent> first = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 2);
            assertEquals(2, first.size());

            // Second claim should get the remaining 1
            List<OutboxEvent> second = store().claimPending(conn, "owner-2", now, lockExpiry, Duration.ZERO, 5);
            assertEquals(1, second.size());
            // Verify no overlap
            assertNotEquals(first.get(0).eventId(), second.get(0).eventId());
            assertNotEquals(first.get(1).eventId(), second.get(0).eventId());
        }
    }

    @Test
    void claimPendingReclaimsExpiredLocks() throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, EventEnvelope.ofJson("ExpireEvent", "{}"));

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);

            // First claim
            List<OutboxEvent> first = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(1, first.size());

            // Second claim with lockExpiry in the future (existing lock is expired)
            Instant later = now.plusSeconds(120);
            List<OutboxEvent> reclaimed = store().claimPending(conn, "owner-2", later, now.plusSeconds(1), Duration.ZERO, 10);
            assertEquals(1, reclaimed.size());
            assertEquals(first.get(0).eventId(), reclaimed.get(0).eventId());
        }
    }

    @Test
    void queryDeadReturnsDeadEvents() throws Exception {
        EventEnvelope e1 = EventEnvelope.ofJson("DeadQuery", "{\"n\":1}");
        EventEnvelope e2 = EventEnvelope.ofJson("DeadQuery", "{\"n\":2}");
        EventEnvelope alive = EventEnvelope.ofJson("Alive", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, e1);
            store().insertNew(conn, e2);
            store().insertNew(conn, alive);
            store().markDead(conn, e1.eventId(), "err1");
            store().markDead(conn, e2.eventId(), "err2");

            List<OutboxEvent> dead = store().queryDead(conn, "DeadQuery", null, 10);
            assertEquals(2, dead.size());

            List<OutboxEvent> all = store().queryDead(conn, null, null, 10);
            assertEquals(2, all.size());
        }
    }

    @Test
    void replayDeadResetsToNew() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("ReplayEvent", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);
            store().markDead(conn, envelope.eventId(), "failed");

            int replayed = store().replayDead(conn, envelope.eventId());
            assertEquals(1, replayed);

            // Should now appear in pending
            List<OutboxEvent> pending = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertEquals(1, pending.size());
            assertEquals(0, pending.get(0).attempts());
        }
    }

    @Test
    void countDead() throws Exception {
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            EventEnvelope e1 = EventEnvelope.ofJson("CountType", "{}");
            EventEnvelope e2 = EventEnvelope.ofJson("CountType", "{}");
            EventEnvelope e3 = EventEnvelope.ofJson("OtherType", "{}");
            store().insertNew(conn, e1);
            store().insertNew(conn, e2);
            store().insertNew(conn, e3);
            store().markDead(conn, e1.eventId(), "err");
            store().markDead(conn, e2.eventId(), "err");
            store().markDead(conn, e3.eventId(), "err");

            assertEquals(3, store().countDead(conn, null));
            assertEquals(2, store().countDead(conn, "CountType"));
            assertEquals(1, store().countDead(conn, "OtherType"));
        }
    }

    @Test
    void markDeferredDoesNotIncrementAttempts() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("DeferEvent", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            // First: mark retry to increment attempts to 1
            store().markRetry(conn, envelope.eventId(), Instant.now(), "error1");

            // Now defer — should NOT increment attempts
            Instant nextAt = Instant.now().plusSeconds(60);
            int updated = store().markDeferred(conn, envelope.eventId(), nextAt);
            assertEquals(1, updated);
            // A deferral is not a failure: NEW, matching InMemoryOutboxStore, not RETRY.
            assertEquals(EventStatus.NEW.code(), statusOf(conn, envelope.eventId()));

            // Event should not appear in poll (available_at is in the future)
            List<OutboxEvent> pending = store().pollPending(conn, Instant.now(), Duration.ZERO, 10);
            assertTrue(pending.isEmpty());

            // Defer again with immediate nextAt and check attempts stayed at 1
            Instant now = Instant.now();
            store().markDeferred(conn, envelope.eventId(), now);
            pending = store().pollPending(conn, now.plusSeconds(1), Duration.ZERO, 10);
            assertEquals(1, pending.size());
            assertEquals(1, pending.get(0).attempts(), "markDeferred should not increment attempts");
        }
    }

    @Test
    void markDeferredOnTerminalEventIsNoOp() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("DeferTerminal", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);
            store().markDone(conn, envelope.eventId());

            int updated = store().markDeferred(conn, envelope.eventId(), Instant.now());
            assertEquals(0, updated, "markDeferred should not update terminal events");
        }
    }

    @Test
    void claimPendingExcludesDelayedEvents() throws Exception {
        Instant future = Instant.now().plusSeconds(3600);
        EventEnvelope delayed = EventEnvelope.builder("ClaimDelayed")
                .payloadJson("{}")
                .availableAt(future)
                .build();
        EventEnvelope immediate = EventEnvelope.ofJson("ClaimImmediate", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, delayed);
            store().insertNew(conn, immediate);

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);
            List<OutboxEvent> claimed = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(1, claimed.size());
            assertEquals(immediate.eventId(), claimed.get(0).eventId());
        }
    }

    @Test
    void claimPendingExcludesRetryWithFutureAvailableAt() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("ClaimRetry", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            // Mark retry with future available_at
            Instant futureAt = Instant.now().plusSeconds(3600);
            store().markRetry(conn, envelope.eventId(), futureAt, "will retry later");

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);
            List<OutboxEvent> claimed = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 10);
            assertTrue(claimed.isEmpty(), "claimPending should exclude events with future available_at from markRetry");
        }
    }

    @Test
    void claimPendingExcludesDeferredWithFutureAvailableAt() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("ClaimDefer", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            // Defer with future available_at
            Instant futureAt = Instant.now().plusSeconds(3600);
            store().markDeferred(conn, envelope.eventId(), futureAt);

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);
            List<OutboxEvent> claimed = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 10);
            assertTrue(claimed.isEmpty(), "claimPending should exclude events with future available_at from markDeferred");
        }
    }

    @Test
    void claimPendingIncludesEventsAfterAvailableAtPasses() throws Exception {
        EventEnvelope envelope = EventEnvelope.ofJson("ClaimAfterDelay", "{}");

        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, envelope);

            // Defer to a past time (simulating delay has elapsed)
            Instant pastAt = Instant.now().minusSeconds(10);
            store().markDeferred(conn, envelope.eventId(), pastAt);

            Instant now = Instant.now();
            Instant lockExpiry = now.minusSeconds(60);
            List<OutboxEvent> claimed = store().claimPending(conn, "owner-1", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(1, claimed.size(), "claimPending should include events whose available_at has passed");
            assertEquals(envelope.eventId(), claimed.get(0).eventId());
        }
    }

    /**
     * Claim fencing in the real SQL: an old lease's writes miss once the row was taken over, even
     * after the new holder's nonterminal write left it unclaimed; the exact current lease lands.
     */
    @Test
    void fencedWritesNeedTheExactCurrentLease() throws Exception {
        EventEnvelope event = EventEnvelope.ofJson("FenceEvent", "{}");
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            store().insertNew(conn, event);
            Instant now = Instant.now();
            assertEquals(1, store().claimPending(conn, "owner-1", now, now.minusSeconds(60), Duration.ZERO, 10).size());
            Instant leaseA = store().leaseTimestamp(now);
            Instant later = now.plusSeconds(120);
            assertEquals(1, store().claimPending(conn, "owner-2", later, now.plusSeconds(1), Duration.ZERO, 10).size());
            Instant leaseB = store().leaseTimestamp(later);

            String id = event.eventId();
            assertEquals(0, store().markDone(conn, id, "owner-1", leaseA));
            assertEquals(0, store().markRetry(conn, id, later, "late", "owner-1", leaseA));
            assertEquals(0, store().markDead(conn, id, "late", "owner-1", leaseA));
            assertEquals(0, store().markDeferred(conn, id, later, "owner-1", leaseA));
            assertEquals(EventStatus.NEW.code(), statusOf(conn, id));

            assertEquals(1, store().markRetry(conn, id, later, "boom", "owner-2", leaseB));
            assertEquals(0, store().markDead(conn, id, "late", "owner-1", leaseA), "unclaimed is not authority");
            assertEquals(EventStatus.RETRY.code(), statusOf(conn, id));
        }
    }

    /**
     * releaseStamp drops only an unclaimed writer stamp, which the SQL recognises by
     * {@code locked_at = created_at}: a claim (the owner's own too), another owner's stamp and a
     * settled row are left alone, and a released row is claimable by any node at once.
     */
    @Test
    void releaseStampClearsOnlyAnUnclaimedStamp() throws Exception {
        AbstractJdbcOutboxStore podA = stampingStore("pod-a");
        EventEnvelope claimed = EventEnvelope.ofJson("StampEvent", "{}");
        EventEnvelope stamped = EventEnvelope.ofJson("StampEvent", "{}");
        EventEnvelope settled = EventEnvelope.ofJson("StampEvent", "{}");
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            podA.insertNew(conn, claimed);
            Instant now = Instant.now();
            assertEquals(1, podA.claimPending(conn, "pod-a", now, now.minusSeconds(60), Duration.ZERO, 10).size());
            podA.insertNew(conn, stamped);
            podA.insertNew(conn, settled);
            settleKeepingTheStamp(conn, settled.eventId());

            assertEquals(0, podA.releaseStamp(conn, claimed.eventId(), "pod-a"), "a claim is not a stamp");
            assertEquals(0, podA.releaseStamp(conn, settled.eventId(), "pod-a"), "a settled row stays settled");
            assertEquals(0, podA.releaseStamp(conn, stamped.eventId(), "pod-b"), "only the stamp's owner");
            assertEquals(1, podA.releaseStamp(conn, stamped.eventId(), "pod-a"));

            Instant next = Instant.now();
            List<OutboxEvent> taken = store().claimPending(conn, "pod-b", next, next.minusSeconds(60), Duration.ZERO, 10);
            assertEquals(List.of(stamped.eventId()), taken.stream().map(OutboxEvent::eventId).toList(),
                    "another node takes the released row at once; the claimed one stays claimed");
        }
    }

    /**
     * Ending a run leaves exactly its stamps to the sweep. A later run — over the same store, or a new
     * store with the same owner — is out of its reach, and stays its own run's to claim early.
     */
    @Test
    void aSweepOfAnEndedRunCannotTouchALaterRun() throws Exception {
        AbstractJdbcOutboxStore podA = stampingStore("pod-a");
        EventEnvelope old = EventEnvelope.ofJson("StampEvent", "{}");
        EventEnvelope reused = EventEnvelope.ofJson("StampEvent", "{}");
        EventEnvelope rebuilt = EventEnvelope.ofJson("StampEvent", "{}");
        try (Connection conn = dataSource().getConnection()) {
            conn.setAutoCommit(true);
            podA.insertNew(conn, old);
            String ended = podA.endStampRun().orElseThrow();
            podA.insertNew(conn, reused);
            stampingStore("pod-a").insertNew(conn, rebuilt);

            assertEquals(List.of(old.eventId()), podA.findStamped(conn, ended, 10));
            OutboxStore.ReleaseResult result = podA.releaseHeld(conn, List.of(
                    new OutboxStore.Held(old.eventId(), ended, null),
                    new OutboxStore.Held(reused.eventId(), ended, null),
                    new OutboxStore.Held(rebuilt.eventId(), ended, null)));
            assertEquals(new OutboxStore.ReleaseResult(1, 0, null), result);

            Instant now = Instant.now();
            assertEquals(List.of(old.eventId(), reused.eventId()).stream().sorted().toList(),
                    ids(podA.claimPending(conn, "pod-a", now, now.minusSeconds(60), Duration.ZERO, 10)).stream()
                            .sorted().toList(),
                    "the swept row is unowned, the store's current run is its own; the rebuilt store's run is not");
        }
    }

    /**
     * The sweep reads without locks and releases by exact id, so another owner's row held by a
     * concurrent transaction neither blocks it nor is touched — where one broad UPDATE over the
     * pending rows would wait on that row's lock.
     */
    @Test
    void theSweepDoesNotWaitOnAnotherOwnersLockedRow() throws Exception {
        AbstractJdbcOutboxStore podA = stampingStore("pod-a");
        EventEnvelope mine = EventEnvelope.ofJson("StampEvent", "{}");
        EventEnvelope theirs = EventEnvelope.ofJson("StampEvent", "{}");
        try (Connection setup = dataSource().getConnection()) {
            setup.setAutoCommit(true);
            stampingStore("pod-b").insertNew(setup, theirs);
            podA.insertNew(setup, mine);
        }
        String ended = podA.endStampRun().orElseThrow();
        try (Connection holder = dataSource().getConnection(); Connection sweeper = dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT event_id FROM outbox_event WHERE event_id = ? FOR UPDATE")) {
                lock.setString(1, theirs.eventId());
                try (ResultSet rs = lock.executeQuery()) {
                    assertTrue(rs.next());
                }
            }
            sweeper.setAutoCommit(true);
            shortLockWait(sweeper);

            List<String> found = podA.findStamped(sweeper, ended, 10);
            OutboxStore.ReleaseResult result = podA.releaseHeld(sweeper,
                    found.stream().map(id -> new OutboxStore.Held(id, ended, null)).toList());

            assertEquals(List.of(mine.eventId()), found);
            assertEquals(new OutboxStore.ReleaseResult(1, 0, null), result);
            holder.rollback();
        }
    }

    private static List<String> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::eventId).toList();
    }

    @Test
    void autoDetectFromDataSource() {
        AbstractJdbcOutboxStore detected = JdbcOutboxStores.detect(dataSource());
        assertEquals(store().name(), detected.name());
    }

    /** DONE with the stamp still on the row; the store's own terminal writes clear it. */
    private static void settleKeepingTheStamp(Connection conn, String eventId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE outbox_event SET status = ? WHERE event_id = ?")) {
            ps.setInt(1, EventStatus.DONE.code());
            ps.setString(2, eventId);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static int statusOf(Connection conn, String eventId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM outbox_event WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "event row missing: " + eventId);
                return rs.getInt(1);
            }
        }
    }
}
