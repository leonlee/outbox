package outbox.jdbc;

import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.store.JdbcOutboxStores;
import outbox.model.OutboxEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstract base for outbox store integration tests against real databases.
 * Subclasses provide the DataSource and store instance.
 */
abstract class AbstractOutboxStoreIntegrationTest {

    abstract DataSource dataSource();

    abstract AbstractJdbcOutboxStore store();

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
    void autoDetectFromDataSource() {
        AbstractJdbcOutboxStore detected = JdbcOutboxStores.detect(dataSource());
        assertEquals(store().name(), detected.name());
    }
}
