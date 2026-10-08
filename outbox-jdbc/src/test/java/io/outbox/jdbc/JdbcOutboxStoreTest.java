package io.outbox.jdbc;

import io.outbox.EventEnvelope;
import io.outbox.jdbc.store.H2OutboxStore;
import io.outbox.model.EventStatus;
import io.outbox.model.OutboxEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOutboxStoreTest {

    private static final String TABLE = "outbox_event";
    private static final String POD_A = "pod-a";

    private JdbcDataSource dataSource;
    private H2OutboxStore outboxStore;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(
                    "CREATE TABLE outbox_event (" +
                            "event_id VARCHAR(36) PRIMARY KEY," +
                            "event_type VARCHAR(128) NOT NULL," +
                            "aggregate_type VARCHAR(64)," +
                            "aggregate_id VARCHAR(128)," +
                            "tenant_id VARCHAR(64)," +
                            "payload CLOB NOT NULL," +
                            "headers CLOB," +
                            "status TINYINT NOT NULL," +
                            "attempts INT NOT NULL DEFAULT 0," +
                            "available_at TIMESTAMP NOT NULL," +
                            "created_at TIMESTAMP NOT NULL," +
                            "done_at TIMESTAMP," +
                            "last_error CLOB," +
                            "locked_by VARCHAR(128)," +
                            "locked_at TIMESTAMP" +
                            ")"
            );
        }

        outboxStore = new H2OutboxStore();
    }

    @Test
    void insertNewCreatesRowWithCorrectStatus() throws SQLException {
        EventEnvelope event = EventEnvelope.builder("TestEvent")
                .aggregateType("Test")
                .aggregateId("123")
                .payloadJson("{\"test\":true}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, attempts FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, event.eventId());
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.NEW.code(), rs.getInt("status"));
                assertEquals(0, rs.getInt("attempts"));
            }
        }
    }

    @Test
    void insertNewStoresAllFields() throws SQLException {
        EventEnvelope event = EventEnvelope.builder("UserCreated")
                .eventId("custom-id")
                .aggregateType("User")
                .aggregateId("user-123")
                .tenantId("tenant-A")
                .headers(Map.of("key", "value"))
                .payloadJson("{\"name\":\"Alice\"}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, "custom-id");
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("UserCreated", rs.getString("event_type"));
                assertEquals("User", rs.getString("aggregate_type"));
                assertEquals("user-123", rs.getString("aggregate_id"));
                assertEquals("tenant-A", rs.getString("tenant_id"));
                assertEquals("{\"name\":\"Alice\"}", rs.getString("payload"));
                assertTrue(rs.getString("headers").contains("key"));
            }
        }
    }

    @Test
    void markDoneUpdatesStatusAndSetsTimestamp() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            int updated = outboxStore.markDone(conn, eventId);

            assertEquals(1, updated);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, done_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.DONE.code(), rs.getInt("status"));
                assertNotNull(rs.getTimestamp("done_at"));
            }
        }
    }

    @Test
    void markDoneIsIdempotent() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            int first = outboxStore.markDone(conn, eventId);
            int second = outboxStore.markDone(conn, eventId);

            assertEquals(1, first);
            assertEquals(0, second); // Already DONE, no update
        }
    }

    @Test
    void markRetryUpdatesStatusAndIncrementsAttempts() throws SQLException {
        String eventId = insertTestEvent();
        Instant nextAt = Instant.now().plusSeconds(60);

        try (Connection conn = dataSource.getConnection()) {
            int updated = outboxStore.markRetry(conn, eventId, nextAt, "Connection failed");

            assertEquals(1, updated);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, attempts, last_error FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.RETRY.code(), rs.getInt("status"));
                assertEquals(1, rs.getInt("attempts"));
                assertEquals("Connection failed", rs.getString("last_error"));
            }
        }
    }

    /** A deferral is not a failure: NEW, as InMemoryOutboxStore has it, with attempts untouched. */
    @Test
    void markDeferredResetsToNewWithoutCountingAnAttempt() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markRetry(conn, eventId, Instant.now(), "first failure");
            assertEquals(1, outboxStore.markDeferred(conn, eventId, Instant.now().plusSeconds(60)));

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, attempts FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.NEW.code(), rs.getInt("status"));
                assertEquals(1, rs.getInt("attempts"));
            }
        }
    }

    @Test
    void markRetryTruncatesLongError() throws SQLException {
        String eventId = insertTestEvent();
        StringBuilder longError = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longError.append("x");
        }

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markRetry(conn, eventId, Instant.now(), longError.toString());

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT last_error FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                String storedError = rs.getString("last_error");
                assertTrue(storedError.length() <= 4000);
                assertTrue(storedError.endsWith("..."));
            }
        }
    }

    @Test
    void markDeadUpdatesStatusAndStoresError() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            int updated = outboxStore.markDead(conn, eventId, "Max retries exceeded");

            assertEquals(1, updated);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, last_error FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.DEAD.code(), rs.getInt("status"));
                assertEquals("Max retries exceeded", rs.getString("last_error"));
            }
        }
    }

    @Test
    void pollPendingReturnsNewEvents() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now().plusSeconds(1),
                    Duration.ZERO, 10);

            assertEquals(1, rows.size());
            assertEquals(eventId, rows.get(0).eventId());
        }
    }

    @Test
    void pollPendingReturnsRetryEvents() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markRetry(conn, eventId, Instant.now().minusSeconds(10), "error");

            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now(),
                    Duration.ZERO, 10);

            assertEquals(1, rows.size());
            assertEquals(eventId, rows.get(0).eventId());
        }
    }

    @Test
    void pollPendingExcludesDoneEvents() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDone(conn, eventId);

            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now().plusSeconds(1),
                    Duration.ZERO, 10);

            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void pollPendingExcludesDeadEvents() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDead(conn, eventId, "dead");

            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now().plusSeconds(1),
                    Duration.ZERO, 10);

            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void pollPendingRespectsSkipRecent() throws SQLException {
        insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            // Skip events created in the last hour
            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now(),
                    Duration.ofHours(1), 10);

            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void pollPendingRespectsLimit() throws SQLException {
        for (int i = 0; i < 5; i++) {
            insertTestEvent();
        }

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now().plusSeconds(1),
                    Duration.ZERO, 3);

            assertEquals(3, rows.size());
        }
    }

    @Test
    void pollPendingRespectsAvailableAt() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            // Set available_at to future
            outboxStore.markRetry(conn, eventId, Instant.now().plus(Duration.ofHours(1)), "delayed");

            List<OutboxEvent> rows = outboxStore.pollPending(conn, Instant.now(),
                    Duration.ZERO, 10);

            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void claimPendingLocksRows() throws SQLException {
        insertTestEvent();
        insertTestEvent();
        insertTestEvent();

        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> claimed = outboxStore.claimPending(
                    conn, "owner-A", now, lockExpiry, Duration.ZERO, 2);

            assertEquals(2, claimed.size());

            // Verify lock columns are set
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by, locked_at FROM outbox_event WHERE locked_by IS NOT NULL")) {
                ResultSet rs = ps.executeQuery();
                int lockedCount = 0;
                while (rs.next()) {
                    assertEquals("owner-A", rs.getString("locked_by"));
                    assertNotNull(rs.getTimestamp("locked_at"));
                    lockedCount++;
                }
                assertEquals(2, lockedCount);
            }
        }
    }

    @Test
    void claimPendingSkipsLockedRows() throws SQLException {
        insertTestEvent();
        insertTestEvent();

        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            List<OutboxEvent> claimedA = outboxStore.claimPending(
                    conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(2, claimedA.size());

            // owner-B should get 0 because all are locked by owner-A with non-expired locks
            List<OutboxEvent> claimedB = outboxStore.claimPending(
                    conn, "owner-B", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(0, claimedB.size());
        }
    }

    @Test
    void claimPendingReclaimsExpiredLocks() throws SQLException {
        insertTestEvent();

        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            // owner-A claims the event
            List<OutboxEvent> claimedA = outboxStore.claimPending(
                    conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(1, claimedA.size());

            // Simulate expired lock by setting locked_at to the past
            conn.createStatement().execute(
                    "UPDATE outbox_event SET locked_at = TIMESTAMP '2020-01-01 00:00:00'");

            // owner-B should reclaim the expired lock
            List<OutboxEvent> claimedB = outboxStore.claimPending(
                    conn, "owner-B", now, lockExpiry, Duration.ZERO, 10);
            assertEquals(1, claimedB.size());

            // Verify lock is now owner-B
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, claimedB.get(0).eventId());
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("owner-B", rs.getString("locked_by"));
            }
        }
    }

    @Test
    void markDoneClearsLock() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            Instant now = Instant.now().plusSeconds(1);
            Instant lockExpiry = now.minus(Duration.ofMinutes(5));
            outboxStore.claimPending(conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);

            outboxStore.markDone(conn, eventId);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by, locked_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertNull(rs.getString("locked_by"));
                assertNull(rs.getTimestamp("locked_at"));
            }
        }
    }

    @Test
    void markRetryClearsLock() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            Instant now = Instant.now().plusSeconds(1);
            Instant lockExpiry = now.minus(Duration.ofMinutes(5));
            outboxStore.claimPending(conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);

            outboxStore.markRetry(conn, eventId, Instant.now().plusSeconds(60), "retry error");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by, locked_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertNull(rs.getString("locked_by"));
                assertNull(rs.getTimestamp("locked_at"));
            }
        }
    }

    @Test
    void markDeadClearsLock() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            Instant now = Instant.now().plusSeconds(1);
            Instant lockExpiry = now.minus(Duration.ofMinutes(5));
            outboxStore.claimPending(conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);

            outboxStore.markDead(conn, eventId, "dead");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by, locked_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertNull(rs.getString("locked_by"));
                assertNull(rs.getTimestamp("locked_at"));
            }
        }
    }

    @Test
    void markDoneReturnsZeroForDeadEvent() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDead(conn, eventId, "dead");
            int updated = outboxStore.markDone(conn, eventId);

            assertEquals(0, updated);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.DEAD.code(), rs.getInt("status"));
            }
        }
    }

    @Test
    void markRetryReturnsZeroForDeadEvent() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDead(conn, eventId, "dead");
            int updated = outboxStore.markRetry(conn, eventId, Instant.now().plusSeconds(60), "retry");

            assertEquals(0, updated);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, eventId);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(EventStatus.DEAD.code(), rs.getInt("status"));
            }
        }
    }

    @Test
    void markDeadIsIdempotent() throws SQLException {
        String eventId = insertTestEvent();

        try (Connection conn = dataSource.getConnection()) {
            int first = outboxStore.markDead(conn, eventId, "dead");
            int second = outboxStore.markDead(conn, eventId, "dead again");

            assertEquals(1, first);
            assertEquals(0, second);
        }
    }

    @Test
    void insertBatchWithSingleEvent() throws SQLException {
        EventEnvelope event = EventEnvelope.builder("SingleBatch")
                .payloadJson("{\"n\":1}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertBatch(conn, List.of(event));
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM outbox_event")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void insertBatchWithMultipleEvents() throws SQLException {
        List<EventEnvelope> events = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(EventEnvelope.builder("BatchEvent")
                    .eventId("batch-" + i)
                    .aggregateType("Order")
                    .aggregateId("order-" + i)
                    .payloadJson("{\"n\":" + i + "}")
                    .build());
        }

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertBatch(conn, events);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM outbox_event")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
        }

        // Verify each event is stored correctly
        for (int i = 0; i < 5; i++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT event_type, aggregate_id, payload FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, "batch-" + i);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "Event batch-" + i + " should exist");
                assertEquals("BatchEvent", rs.getString("event_type"));
                assertEquals("order-" + i, rs.getString("aggregate_id"));
                assertEquals("{\"n\":" + i + "}", rs.getString("payload"));
            }
        }
    }

    @Test
    void insertBatchWithEmptyList() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertBatch(conn, List.of());
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM outbox_event")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void insertBatchChunksLargeBatches() throws SQLException {
        // Insert > 500 events to trigger chunking (MAX_BATCH_ROWS = 500)
        int count = 510;
        List<EventEnvelope> events = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(EventEnvelope.builder("Chunk")
                    .eventId("chunk-" + i)
                    .payloadJson("{}")
                    .build());
        }

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertBatch(conn, events);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM outbox_event")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(count, rs.getInt(1));
        }
    }

    @Test
    void insertBatchPreservesHeaders() throws SQLException {
        EventEnvelope event = EventEnvelope.builder("HeaderBatch")
                .eventId("hdr-batch-1")
                .headers(Map.of("trace-id", "abc123"))
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertBatch(conn, List.of(event, EventEnvelope.builder("HeaderBatch")
                    .eventId("hdr-batch-2")
                    .payloadJson("{}")
                    .build()));
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT headers FROM outbox_event WHERE event_id = ?")) {
            ps.setString(1, "hdr-batch-1");
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertTrue(rs.getString("headers").contains("trace-id"));
        }
    }

    @Test
    void claimPendingRejectsNullOwnerId() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThrows(NullPointerException.class, () ->
                    outboxStore.claimPending(conn, null, Instant.now(),
                            Instant.now().minus(Duration.ofMinutes(5)), Duration.ZERO, 10));
        }
    }

    @Test
    void storeNameAndPrefixes() {
        assertEquals("h2", outboxStore.name());
        assertEquals(List.of("jdbc:h2:"), outboxStore.jdbcUrlPrefixes());
    }

    @Test
    void insertNewUsesAvailableAtWhenSet() throws SQLException {
        Instant occurredAt = Instant.parse("2025-06-01T12:00:00Z");
        Instant availableAt = Instant.parse("2025-06-01T13:00:00Z");
        EventEnvelope event = EventEnvelope.builder("Delayed")
                .occurredAt(occurredAt)
                .availableAt(availableAt)
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT available_at, created_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, event.eventId());
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(java.sql.Timestamp.from(availableAt), rs.getTimestamp("available_at"));
                assertEquals(java.sql.Timestamp.from(occurredAt), rs.getTimestamp("created_at"));
            }
        }
    }

    @Test
    void insertNewDefaultsAvailableAtToOccurredAt() throws SQLException {
        EventEnvelope event = EventEnvelope.builder("Immediate")
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT available_at, created_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, event.eventId());
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(rs.getTimestamp("created_at"), rs.getTimestamp("available_at"));
            }
        }
    }

    @Test
    void insertBatchUsesAvailableAtWhenSet() throws SQLException {
        Instant occurredAt = Instant.parse("2025-06-01T12:00:00Z");
        Instant availableAt = Instant.parse("2025-06-01T14:00:00Z");
        EventEnvelope delayed = EventEnvelope.builder("BatchDelayed")
                .eventId("batch-delayed")
                .occurredAt(occurredAt)
                .availableAt(availableAt)
                .payloadJson("{}")
                .build();
        EventEnvelope immediate = EventEnvelope.builder("BatchImmediate")
                .eventId("batch-immediate")
                .occurredAt(occurredAt)
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertBatch(conn, List.of(delayed, immediate));

            // Verify delayed event has future available_at
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT available_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, "batch-delayed");
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(java.sql.Timestamp.from(availableAt), rs.getTimestamp("available_at"));
            }

            // Verify immediate event has available_at = created_at
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT available_at, created_at FROM outbox_event WHERE event_id = ?")) {
                ps.setString(1, "batch-immediate");
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(rs.getTimestamp("created_at"), rs.getTimestamp("available_at"));
            }
        }
    }

    @Test
    void delayedEventNotReturnedByPollPendingUntilAvailable() throws SQLException {
        Instant now = Instant.now();
        EventEnvelope delayed = EventEnvelope.builder("FutureEvent")
                .occurredAt(now)
                .deliverAfter(Duration.ofHours(1))
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, delayed);

            // Poll at current time — delayed event should not be returned
            List<OutboxEvent> pending = outboxStore.pollPending(conn,
                    now.plusSeconds(1), Duration.ZERO, 10);
            assertTrue(pending.isEmpty());

            // Poll at future time — delayed event should now be returned
            List<OutboxEvent> future = outboxStore.pollPending(conn,
                    now.plusSeconds(3601), Duration.ZERO, 10);
            assertEquals(1, future.size());
            assertEquals(delayed.eventId(), future.get(0).eventId());
        }
    }

    // ── releaseClaim: hand a lease back without resurrecting anything ──

    @Test
    void releaseClaimFreesAPendingRowForTheNextPoll() throws SQLException {
        insertTestEvent();
        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(1, outboxStore.claimPending(conn, POD_A, now, lockExpiry, Duration.ZERO, 10).size());
            assertEquals(0, outboxStore.claimPending(conn, "pod-b", now, lockExpiry, Duration.ZERO, 10).size(),
                    "leased, so nobody else gets it");

            assertEquals(1, outboxStore.releaseClaim(conn, eventIdOf(conn), POD_A, leaseOf(conn)));

            assertEquals(1, outboxStore.claimPending(conn, "pod-b", now, lockExpiry, Duration.ZERO, 10).size(),
                    "lease handed back — no need to wait out the lock timeout");
        }
    }

    @Test
    void releaseClaimLeavesASettledRowAlone() throws SQLException {
        String eventId = insertTestEvent();
        try (Connection conn = dataSource.getConnection()) {
            outboxStore.markDone(conn, eventId);

            // The guard that stops this being a resurrection: a completed event must never become
            // claimable again just because some dispatch was abandoned.
            assertEquals(0, outboxStore.releaseClaim(conn, eventId, POD_A, Instant.now()));

            Instant now = Instant.now().plusSeconds(1);
            assertEquals(0, outboxStore.claimPending(
                    conn, POD_A, now, now.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size());
        }
    }

    @Test
    void releaseClaimRefusesToClearSomebodyElsesLease() throws SQLException {
        insertTestEvent();
        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(1, outboxStore.claimPending(conn, POD_A, now, lockExpiry, Duration.ZERO, 10).size());

            // A copy queued under an older lease arriving after pod-b took the row over. Clearing
            // pod-b's lease here would put the event back in circulation while pod-b delivers it.
            assertEquals(0, outboxStore.releaseClaim(conn, eventIdOf(conn), "pod-b", leaseOf(conn)));
            assertEquals(0, outboxStore.claimPending(conn, "pod-c", now, lockExpiry, Duration.ZERO, 10).size(),
                    "the lease must still be pod-a's");
        }
    }

    /**
     * A release must name the exact lease it is undoing, not just the owner. An owner id lives as
     * long as the pod, so once a lease expires and the SAME pod re-claims the row, a copy queued
     * under the old lease would otherwise clear the new one — and the copy riding that new lease
     * goes on delivering while the row is back in circulation.
     */
    @Test
    void releaseClaimRefusesAnOlderLeaseOfTheSameOwner() throws SQLException {
        insertTestEvent();
        Instant now = Instant.now().plusSeconds(1);

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(1, outboxStore.claimPending(
                    conn, POD_A, now, now.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size());
            Instant firstLease = leaseOf(conn);

            // The lease lapses and pod-a takes the row again: same owner, new locked_at.
            Instant later = now.plusSeconds(600);
            assertEquals(1, outboxStore.claimPending(
                    conn, POD_A, later, later.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size());
            assertNotEquals(firstLease, leaseOf(conn), "the re-claim must stamp a new lease");

            assertEquals(0, outboxStore.releaseClaim(conn, eventIdOf(conn), POD_A, firstLease),
                    "a copy from the lapsed lease must not clear the current one");
            assertEquals(0, outboxStore.claimPending(
                    conn, "pod-b", later, later.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size(),
                    "so the row stays locked");
        }
    }

    /** The locked_at currently on the single row, i.e. the lease a release must name. */
    private Instant leaseOf(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT locked_at FROM outbox_event");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getTimestamp(1).toInstant();
        }
    }

    private String eventIdOf(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT event_id FROM outbox_event");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    // ── Writer-stamped ownership ────────────────────────────────────

    @Test
    void writerOwnerIsStampedAtInsert() throws SQLException {
        H2OutboxStore owned = new H2OutboxStore(TABLE, POD_A);
        try (Connection conn = dataSource.getConnection()) {
            owned.insertNew(conn, EventEnvelope.builder("TestEvent").payloadJson("{}").build());

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by, locked_at FROM outbox_event");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(POD_A, rs.getString("locked_by"),
                        "the row must be owned the moment it becomes visible to other instances");
                assertNotNull(rs.getTimestamp("locked_at"));
            }
        }
    }

    /**
     * A reservation that is already expired is worse than none, so it is not written.
     *
     * <p>{@code locked_at} is set from the event's own {@code occurredAt}, which is also
     * {@code created_at}. A backfilled event — or one whose transaction outlived the lock timeout
     * before committing — would therefore arrive carrying a lease older than any {@code lockExpiry}
     * the poller computes, so every instance reads it as expired and claims it at once. That is the
     * opposite of a reservation, and it reads from the configuration as though the row were
     * protected.
     */
    @Test
    void anEventTooOldToReserveIsInsertedUnowned() throws SQLException {
        H2OutboxStore owned = new H2OutboxStore(TABLE, POD_A);
        try (Connection conn = dataSource.getConnection()) {
            owned.insertNew(conn, EventEnvelope.builder("TestEvent")
                    .occurredAt(Instant.now().minus(Duration.ofHours(2)))
                    .payloadJson("{}").build());

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT locked_by, locked_at FROM outbox_event");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("locked_by"),
                        "a lease that is born expired must not be written at all");
                assertNull(rs.getTimestamp("locked_at"));
            }
        }
    }

    @Test
    void writerOwnerIsAbsentByDefault() throws SQLException {
        insertTestEvent();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT locked_by FROM outbox_event");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertNull(rs.getString("locked_by"), "unowned unless a writer owner is configured");
        }
    }

    @Test
    void anotherInstanceCannotClaimRowsWeWrote() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, EventEnvelope.builder("TestEvent").payloadJson("{}").build());

            // This is the cross-pod duplicate: pod-a still holds the hot copy in its own JVM,
            // so pod-b claiming the row here is what produces a second delivery no in-memory
            // guard can see.
            assertEquals(0, podA.claimPending(conn, "pod-b", now, lockExpiry, Duration.ZERO, 10).size());
        }
    }

    @Test
    void weCanClaimOurOwnRowsImmediately() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, EventEnvelope.builder("TestEvent").payloadJson("{}").build());

            // Many writes never enter the hot queue. If the writer had to wait out
            // the lock timeout for its own rows, ownership would turn a 1s latency into 5 minutes.
            assertEquals(1, podA.claimPending(conn, POD_A, now, lockExpiry, Duration.ZERO, 10).size());
        }
    }

    /**
     * A poller lease must never look like a writer stamp. The stamp is recognised by
     * {@code locked_at = created_at} alone, so a claim taken in the same millisecond as a
     * millisecond-aligned {@code created_at} used to read as an unclaimed stamp, and the next poll
     * re-claimed the row while the lease was still valid. No writer owner here: the default
     * configuration was exposed too.
     */
    @Test
    void aClaimInCreatedAtsOwnMillisecondStillHoldsItsLease() throws SQLException {
        H2OutboxStore store = new H2OutboxStore();
        Instant created = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        try (Connection conn = dataSource.getConnection()) {
            store.insertNew(conn, EventEnvelope.builder("TestEvent").occurredAt(created).payloadJson("{}").build());
            assertEquals(1, store.claimPending(
                    conn, POD_A, created, created.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size());

            Instant later = created.plusSeconds(1);
            assertEquals(0, store.claimPending(
                    conn, POD_A, later, later.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size(),
                    "the first lease is valid for five minutes, so a poll one second later must get nothing");
        }
    }

    /**
     * The self-claim allowance has to be ONE-SHOT. A standing {@code locked_by = <self>} exemption
     * lets every subsequent poll re-claim the same still-PENDING row and enqueue another copy —
     * the cold queue fills with duplicates of events already queued and newer events starve.
     *
     * <p>What separates the two states is that the writer stamp leaves
     * {@code locked_at = created_at} while a poller claim overwrites {@code locked_at} with the
     * current time.
     */
    @Test
    void ourOwnRowIsClaimableOnceAndNotOnTheNextPoll() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        Instant now = Instant.now().plusSeconds(1);
        Instant lockExpiry = now.minus(Duration.ofMinutes(5));

        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, EventEnvelope.builder("TestEvent").payloadJson("{}").build());

            assertEquals(1, podA.claimPending(conn, POD_A, now, lockExpiry, Duration.ZERO, 10).size(),
                    "first poll picks up its own writer-stamped row");
            assertEquals(0, podA.claimPending(conn, POD_A, now, lockExpiry, Duration.ZERO, 10).size(),
                    "the row is still PENDING but now under an active lease — no second copy");
            assertEquals(0, podA.claimPending(conn, POD_A, now, lockExpiry, Duration.ZERO, 10).size(),
                    "and it stays that way, poll after poll");
        }
    }

    @Test
    void ourOwnLeasedRowIsClaimableAgainOnceTheLeaseExpires() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        Instant now = Instant.now().plusSeconds(1);

        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, EventEnvelope.builder("TestEvent").payloadJson("{}").build());
            assertEquals(1, podA.claimPending(
                    conn, "pod-a", now, now.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size());

            // A worker that died mid-dispatch must not strand the row for its own pod either.
            assertEquals(1, podA.claimPending(
                    conn, "pod-a", now, now.plusSeconds(1), Duration.ZERO, 10).size(),
                    "an expired lease is reclaimable by the owner, same as by anyone else");
        }
    }

    @Test
    void anotherInstanceTakesOverOnceTheLeaseExpires() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        Instant now = Instant.now().plusSeconds(1);

        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, EventEnvelope.builder("TestEvent").payloadJson("{}").build());

            // Ownership must not strand rows when the writing instance dies: with a lockExpiry in
            // the future every stamp counts as stale, which is what the timeout does after PT5M.
            Instant everythingIsStale = now.plusSeconds(1);
            assertEquals(1,
                    podA.claimPending(conn, "pod-b", now, everythingIsStale, Duration.ZERO, 10).size());
        }
    }

    private String insertTestEvent() throws SQLException {
        EventEnvelope event = EventEnvelope.builder("TestEvent")
                .payloadJson("{}")
                .build();

        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);
        }

        return event.eventId();
    }
}
