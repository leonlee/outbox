package io.outbox.jdbc;

import io.outbox.EventEnvelope;
import io.outbox.jdbc.store.H2OutboxStore;
import io.outbox.model.EventStatus;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.OutboxStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
                assertStampOf(POD_A, rs.getString("locked_by"),
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

    // ── Claim fencing on status writes ──────────────────────────────

    /**
     * pod-a's lease lapsed and pod-b took the row over. None of pod-a's late writes may touch it:
     * a RETRY would clear pod-b's lease and let a third instance claim the row mid-delivery.
     */
    @Test
    void fencedWritesSkipARowHeldByAnotherOwner() throws SQLException {
        String eventId = insertTestEvent();
        Instant now = Instant.now();
        try (Connection conn = dataSource.getConnection()) {
            Instant leaseA = claimAs(conn, POD_A, now);
            Instant later = now.plusSeconds(600);
            Instant leaseB = claimAs(conn, "pod-b", later);

            assertEquals(0, outboxStore.markDone(conn, eventId, POD_A, leaseA));
            assertEquals(0, outboxStore.markRetry(conn, eventId, later, "late", POD_A, leaseA));
            assertEquals(0, outboxStore.markDead(conn, eventId, "late", POD_A, leaseA));
            assertEquals(0, outboxStore.markDeferred(conn, eventId, later, POD_A, leaseA));
            assertEquals("pod-b", lockedByOf(conn), "the current holder keeps the row");
            assertEquals(EventStatus.NEW.code(), statusOf(conn, eventId));

            assertEquals(1, outboxStore.markDone(conn, eventId, "pod-b", leaseB));
        }
    }

    /** The fence is the exact lease: an older generation of the same owner cannot clear a newer one. */
    @Test
    void fencedWriteMissesUnderAnOlderLeaseOfTheSameOwner() throws SQLException {
        String eventId = insertTestEvent();
        Instant now = Instant.now();
        try (Connection conn = dataSource.getConnection()) {
            Instant first = claimAs(conn, POD_A, now);
            Instant second = claimAs(conn, POD_A, now.plusSeconds(600));

            assertEquals(0, outboxStore.markRetry(conn, eventId, now, "late", POD_A, first));
            assertEquals(POD_A, lockedByOf(conn));
            assertEquals(1, outboxStore.markDone(conn, eventId, POD_A, second));
        }
    }

    /**
     * The new holder wrote a nonterminal result, which clears its claim. The row being unclaimed
     * again is not authority for the old lease: its late DEAD must not discard the scheduled retry.
     */
    @Test
    void staleWriteCannotOverwriteTheNewHoldersRetry() throws SQLException {
        String eventId = insertTestEvent();
        Instant now = Instant.now();
        try (Connection conn = dataSource.getConnection()) {
            Instant leaseA = claimAs(conn, POD_A, now);
            Instant leaseB = claimAs(conn, "pod-b", now.plusSeconds(600));
            assertEquals(1, outboxStore.markRetry(conn, eventId, now.plusSeconds(3600), "boom", "pod-b", leaseB));

            assertEquals(0, outboxStore.markDead(conn, eventId, "late", POD_A, leaseA));
            assertEquals(EventStatus.RETRY.code(), statusOf(conn, eventId));
        }
    }

    /** No owner means unfenced: the single-node behaviour, and that of the old signatures. */
    @Test
    void writesWithoutAnOwnerAreNotFenced() throws SQLException {
        String eventId = insertTestEvent();
        try (Connection conn = dataSource.getConnection()) {
            claimAs(conn, "pod-b", Instant.now());
            assertEquals(1, outboxStore.markDone(conn, eventId, null, null));
        }
    }

    /**
     * A hot copy (no lease) from a stamping store settles only while its writer stamp is in place.
     * Once a claim replaces the stamp, only that claim's exact lease can write.
     */
    @Test
    void hotWriteOnAStampingStoreNeedsTheStamp() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope stamped = EventEnvelope.builder("TestEvent").payloadJson("{}").build();
        EventEnvelope claimed = EventEnvelope.builder("TestEvent").payloadJson("{}").build();
        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, stamped);
            assertEquals(1, podA.markDone(conn, stamped.eventId(), POD_A, null));

            podA.insertNew(conn, claimed);
            Instant now = Instant.now();
            assertEquals(1, podA.claimPending(conn, POD_A, now, now.minusSeconds(300), Duration.ZERO, 10).size());
            assertEquals(0, podA.markDone(conn, claimed.eventId(), POD_A, null), "the stamp is gone");
            assertEquals(1, podA.markDone(conn, claimed.eventId(), POD_A, podA.leaseTimestamp(now)));
        }
    }

    /**
     * A dropped hot copy's stamp is handed back so any node takes the row at once; a claim, another
     * owner's stamp and a settled row are left alone.
     */
    @Test
    void releaseStampClearsOnlyAnUnclaimedStamp() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope claimed = EventEnvelope.builder("TestEvent").payloadJson("{}").build();
        EventEnvelope settled = EventEnvelope.builder("TestEvent").payloadJson("{}").build();
        EventEnvelope stamped = EventEnvelope.builder("TestEvent").payloadJson("{}").build();
        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, claimed);
            Instant now = Instant.now();
            assertEquals(1, podA.claimPending(conn, POD_A, now, now.minusSeconds(300), Duration.ZERO, 10).size());
            assertEquals(0, podA.releaseStamp(conn, claimed.eventId(), POD_A), "a claim is not a stamp");

            podA.insertNew(conn, settled);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE outbox_event SET status = "
                    + EventStatus.DONE.code() + " WHERE event_id = ?")) {
                ps.setString(1, settled.eventId());
                assertEquals(1, ps.executeUpdate(), "DONE with the stamp kept; markDone would clear it");
            }
            assertEquals(0, podA.releaseStamp(conn, settled.eventId(), POD_A), "a settled row stays settled");

            podA.insertNew(conn, stamped);
            assertEquals(0, podA.releaseStamp(conn, stamped.eventId(), "pod-b"), "only the stamp's owner");
            assertEquals(1, podA.releaseStamp(conn, stamped.eventId(), POD_A));
            claimAs(conn, "pod-b", Instant.now()); // exactly the released row: another node takes it at once
        }
    }

    // ── Stamping runs ──────────────────────────────────────────────

    /**
     * Ending a run leaves exactly its stamps to the sweep. A replacement that stamps afterwards —
     * over this same store, or a new store with the same owner — carries another run, which a late
     * sweep of the ended one can neither find nor release, even by id.
     */
    @Test
    void aSweepOfAnEndedRunCannotTouchALaterRun() throws SQLException {
        H2OutboxStore store = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope old = event();
        EventEnvelope reused = event();
        EventEnvelope rebuilt = event();
        try (Connection conn = dataSource.getConnection()) {
            store.insertNew(conn, old);
            String ended = store.endStampRun().orElseThrow();
            assertEquals(ended, lockedByOf(conn, old.eventId()), "the ended run is the stamp the row carries");

            store.insertNew(conn, reused);
            new H2OutboxStore(TABLE, POD_A).insertNew(conn, rebuilt);
            assertNotEquals(ended, lockedByOf(conn, reused.eventId()), "the same store stamps a new run");
            assertNotEquals(lockedByOf(conn, reused.eventId()), lockedByOf(conn, rebuilt.eventId()));

            assertEquals(List.of(old.eventId()), store.findStamped(conn, ended, 10));
            OutboxStore.ReleaseResult result = store.releaseHeld(conn, List.of(
                    new OutboxStore.Held(old.eventId(), ended, null),
                    new OutboxStore.Held(reused.eventId(), ended, null),
                    new OutboxStore.Held(rebuilt.eventId(), ended, null)));

            assertEquals(1, result.released(), "only the ended run's row");
            assertEquals(0, result.failed());
            assertNull(lockedByOf(conn, old.eventId()));
            assertStampOf(POD_A, lockedByOf(conn, reused.eventId()), "a later run keeps its stamp");
            assertStampOf(POD_A, lockedByOf(conn, rebuilt.eventId()), "a later run keeps its stamp");
            assertEquals(List.of(), store.findStamped(conn, ended, 10));
        }
    }

    /** findStamped has no cursor or sentinel: even an empty event id, which nothing rejects, is found. */
    @Test
    void findStampedFindsAnyEventId() throws SQLException {
        H2OutboxStore store = new H2OutboxStore(TABLE, POD_A);
        try (Connection conn = dataSource.getConnection()) {
            store.insertNew(conn, EventEnvelope.builder("TestEvent").eventId("").payloadJson("{}").build());
            String ended = store.endStampRun().orElseThrow();

            assertEquals(List.of(""), store.findStamped(conn, ended, 10));
        }
    }

    /**
     * A writer and a dispatcher wired with separate store instances of the same owner stamp different
     * runs, yet still agree on one event's stamp: the hot fence and the per-event release match any run
     * of the owner, since the event id pins the run that inserted it. Owner ids with LIKE
     * metacharacters match only themselves.
     */
    @Test
    void separateStoreInstancesOfAnOwnerAgreeOnAnEventsStamp() throws SQLException {
        for (String owner : List.of(POD_A, "p%_!a")) {
            H2OutboxStore writer = new H2OutboxStore(TABLE, owner);
            H2OutboxStore dispatcher = new H2OutboxStore(TABLE, owner);
            EventEnvelope delivered = event();
            EventEnvelope dropped = event();
            try (Connection conn = dataSource.getConnection()) {
                writer.insertNew(conn, delivered);
                writer.insertNew(conn, dropped);

                assertEquals(0, new H2OutboxStore(TABLE, "p").markDone(conn, delivered.eventId(), "p", null),
                        "another owner, even a prefix of this one, is not this stamp's");
                assertEquals(0, new H2OutboxStore(TABLE, owner + "#x").markDone(conn, delivered.eventId(), owner + "#x",
                        null), "nor an owner this one is a prefix of");
                assertEquals(1, dispatcher.markDone(conn, delivered.eventId(), owner, null), owner);
                assertEquals(1, dispatcher.releaseStamp(conn, dropped.eventId(), owner), owner);
            }
        }
    }

    /**
     * One event's stamp is matched against exact values, never a pattern, so an owner id with '#' in it
     * is a different owner: pod-a's hot write or release never touches a row stamped by "pod-a#sibling",
     * nor a 0.13 base-only stamp whose owner id merely looks like one of pod-a's runs.
     */
    @Test
    void aStampOfAnOwnerWithHashIsNotThisOwnersStamp() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope sibling = event();
        EventEnvelope legacy = event();
        try (Connection conn = dataSource.getConnection()) {
            new H2OutboxStore(TABLE, POD_A + "#sibling").insertNew(conn, sibling);
            podA.insertNew(conn, legacy);
            String lookalike = POD_A + "#AAAAAAAAAAAAAAAAAAAAAA"; // a 0.13 owner id shaped like a run stamp
            try (PreparedStatement ps = conn.prepareStatement("UPDATE outbox_event SET locked_by = ? WHERE event_id = ?")) {
                ps.setString(1, lookalike);
                ps.setString(2, legacy.eventId());
                assertEquals(1, ps.executeUpdate());
            }

            for (EventEnvelope foreign : List.of(sibling, legacy)) {
                assertEquals(0, podA.markDone(conn, foreign.eventId(), POD_A, null));
                assertEquals(0, podA.releaseStamp(conn, foreign.eventId(), POD_A));
                assertEquals(new OutboxStore.ReleaseResult(0, 0, null),
                        podA.releaseHeld(conn, List.of(new OutboxStore.Held(foreign.eventId(), POD_A, null))));
            }
        }
    }

    /**
     * The run a store ended last still matches one event's stamp by owner, so the shutdown release can hand
     * back known stamps after the run ended; an earlier ended run does not (one remembered, never a history).
     */
    @Test
    void theLastEndedRunStillMatchesAnEventsStampByOwner() throws SQLException {
        H2OutboxStore store = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope older = event();
        EventEnvelope last = event();
        try (Connection conn = dataSource.getConnection()) {
            store.insertNew(conn, older);
            store.endStampRun();
            store.insertNew(conn, last);
            store.endStampRun();

            assertEquals(1, store.releaseStamp(conn, last.eventId(), POD_A), "the last ended run");
            assertEquals(0, store.releaseStamp(conn, older.eventId(), POD_A), "an earlier one is not remembered");
        }
    }

    /** A store that does not stamp has no run to end. */
    @Test
    void anUnstampedStoreHasNoRunToEnd() {
        assertEquals(Optional.empty(), new H2OutboxStore(TABLE).endStampRun());
    }

    /**
     * Only this run's stamp is the owner's to claim early. A predecessor's — another run of the same
     * owner, or a 0.13 base-only stamp — waits for the lock timeout like any other node's.
     */
    @Test
    void aPredecessorsStampWaitsForTheLockTimeout() throws SQLException {
        H2OutboxStore predecessor = new H2OutboxStore(TABLE, POD_A);
        H2OutboxStore current = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope earlierRun = event();
        EventEnvelope legacy = event();
        try (Connection conn = dataSource.getConnection()) {
            predecessor.insertNew(conn, earlierRun);
            current.insertNew(conn, legacy);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE outbox_event SET locked_by = ? WHERE event_id = ?")) {
                ps.setString(1, POD_A); // as 0.13 stamped it
                ps.setString(2, legacy.eventId());
                assertEquals(1, ps.executeUpdate());
            }
            Instant now = Instant.now();

            assertEquals(List.of(), current.claimPending(conn, POD_A, now, now.minusSeconds(300), Duration.ZERO, 10),
                    "neither is this run's stamp");
            assertEquals(2, current.claimPending(conn, POD_A, now, now.plusSeconds(1), Duration.ZERO, 10).size(),
                    "both are taken once their lock times out");
        }
    }

    /** Matching is exact, so an owner id with '#', '%' or '_' claims only its own stamps. */
    @Test
    void ownerIdsWithMetacharactersMatchOnlyThemselves() throws SQLException {
        String odd = "pod%_#a";
        H2OutboxStore oddStore = new H2OutboxStore(TABLE, odd);
        H2OutboxStore plain = new H2OutboxStore(TABLE, "pod");
        try (Connection conn = dataSource.getConnection()) {
            oddStore.insertNew(conn, event());
            Instant now = Instant.now();

            assertEquals(List.of(), plain.claimPending(conn, "pod", now, now.minusSeconds(300), Duration.ZERO, 10));
            assertEquals(1, oddStore.claimPending(conn, odd, now, now.minusSeconds(300), Duration.ZERO, 10).size());
        }
    }

    /** The stamp is the owner plus a 23-character run suffix; verifySchema refuses one locked_by cannot hold. */
    @Test
    void verifySchemaRefusesAStampWiderThanLockedBy() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertDoesNotThrow(() -> new H2OutboxStore(TABLE, "x".repeat(105)).verifySchema(conn));
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new H2OutboxStore(TABLE, "x".repeat(106)).verifySchema(conn));
            assertTrue(refused.getMessage().contains("at most 105"), refused.getMessage());
        }
    }

    /** One call releases leases and stamps alike; a failure is reported, not thrown. */
    @Test
    void releaseHeldReleasesLeasesAndStampsAndReportsFailures() throws SQLException {
        H2OutboxStore store = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope claimed = event();
        EventEnvelope stamped = event();
        try (Connection conn = dataSource.getConnection()) {
            store.insertNew(conn, claimed);
            Instant now = Instant.now();
            assertEquals(1, store.claimPending(conn, POD_A, now, now.minusSeconds(300), Duration.ZERO, 10).size());
            Instant lease = store.leaseTimestamp(now);
            store.insertNew(conn, stamped);

            OutboxStore.ReleaseResult result = store.releaseHeld(conn, List.of(
                    new OutboxStore.Held(claimed.eventId(), POD_A, lease),
                    new OutboxStore.Held(stamped.eventId(), POD_A, null),
                    new OutboxStore.Held("missing", POD_A, lease)));

            assertEquals(new OutboxStore.ReleaseResult(2, 0, null), result);
        }
        Connection closed = dataSource.getConnection();
        closed.close();
        OutboxStore.ReleaseResult failed = store.releaseHeld(closed, List.of(
                new OutboxStore.Held("a", POD_A, Instant.now()), new OutboxStore.Held("b", POD_A, null)));
        assertEquals(0, failed.released());
        assertEquals(2, failed.failed());
        assertNotNull(failed.firstFailure());
    }

    /**
     * Batch outcomes are counted honestly. A batch that fails as a whole has an unknown outcome
     * (PostgreSQL rolls back an auto-commit batch, MySQL keeps part of it), so its rows are released
     * again one at a time and counted exactly. Counts the driver returned survive an error closing the
     * statement, and a success with an unknown count is neither released nor failed.
     */
    @Test
    void releaseHeldCountsBatchOutcomesHonestly() {
        H2OutboxStore store = new H2OutboxStore(TABLE, POD_A);
        List<OutboxStore.Held> three = List.of(new OutboxStore.Held("a", POD_A, Instant.now()),
                new OutboxStore.Held("b", POD_A, Instant.now()), new OutboxStore.Held("c", POD_A, Instant.now()));
        List<OutboxStore.Held> two = three.subList(0, 2);
        int unknown = Statement.SUCCESS_NO_INFO;

        OutboxStore.ReleaseResult retried = store.releaseHeld(batchConn(() -> {
            throw new BatchUpdateException(new int[]{1, unknown, Statement.EXECUTE_FAILED});
        }, false), three);
        assertEquals(2, retried.released(), "a and c, released again one by one");
        assertEquals(1, retried.failed(), "b fails on its own as well");
        assertNotNull(retried.firstFailure());

        OutboxStore.ReleaseResult closeFailed = store.releaseHeld(batchConn(() -> new int[]{1, 1}, true), two);
        assertEquals(2, closeFailed.released(), "counts returned before the close survive it");
        assertEquals(0, closeFailed.failed());
        assertNotNull(closeFailed.firstFailure());

        assertEquals(new OutboxStore.ReleaseResult(0, 0, null),
                store.releaseHeld(batchConn(() -> new int[]{unknown, unknown}, false), two),
                "done, counts unknown: neither released nor failed");
    }

    @FunctionalInterface
    private interface BatchExecution {
        int[] run() throws SQLException;
    }

    /**
     * A connection whose statements run {@code execution} for executeBatch, may fail to close, and
     * release a single row (executeUpdate) unless its event id is "b".
     */
    private static Connection batchConn(BatchExecution execution, boolean closeFails) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (conn, connMethod, connArgs) -> {
                    if (!"prepareStatement".equals(connMethod.getName())) {
                        return null;
                    }
                    String[] eventId = new String[1];
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                            new Class<?>[]{PreparedStatement.class}, (ps, method, args) -> switch (method.getName()) {
                                case "setString" -> {
                                    if ((int) args[0] == 1) {
                                        eventId[0] = (String) args[1];
                                    }
                                    yield null;
                                }
                                case "executeBatch" -> execution.run();
                                case "executeUpdate" -> {
                                    if ("b".equals(eventId[0])) {
                                        throw new SQLException("lock wait timeout");
                                    }
                                    yield 1;
                                }
                                case "close" -> {
                                    if (closeFails) {
                                        throw new SQLException("connection reset on close");
                                    }
                                    yield null;
                                }
                                default -> null;
                            });
                });
    }

    private static EventEnvelope event() {
        return EventEnvelope.builder("TestEvent").payloadJson("{}").build();
    }

    private String lockedByOf(Connection conn, String eventId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT locked_by FROM outbox_event WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    /** The stamp authorises only its own owner: another owner's hot write must not clear it. */
    @Test
    void hotWriteOnAStampingStoreNeedsTheStampsOwner() throws SQLException {
        H2OutboxStore podA = new H2OutboxStore(TABLE, POD_A);
        EventEnvelope event = EventEnvelope.builder("TestEvent").payloadJson("{}").build();
        try (Connection conn = dataSource.getConnection()) {
            podA.insertNew(conn, event);
            String id = event.eventId();
            Instant later = Instant.now().plusSeconds(60);

            assertEquals(0, podA.markDone(conn, id, "pod-b", null));
            assertEquals(0, podA.markRetry(conn, id, later, "x", "pod-b", null));
            assertEquals(0, podA.markDead(conn, id, "x", "pod-b", null));
            assertEquals(0, podA.markDeferred(conn, id, later, "pod-b", null));
            assertStampOf(POD_A, lockedByOf(conn), "the stamp is untouched");

            assertEquals(1, podA.markDone(conn, id, POD_A, null));
        }
    }

    /** A hot copy from a store without stamping writes only while the row is unclaimed. */
    @Test
    void hotWriteOnANonStampingStoreNeedsAnUnclaimedRow() throws SQLException {
        String unclaimed = insertTestEvent();
        try (Connection conn = dataSource.getConnection()) {
            assertEquals(1, outboxStore.markDone(conn, unclaimed, POD_A, null));

            String claimed = insertTestEvent();
            claimAs(conn, "pod-b", Instant.now());
            assertEquals(0, outboxStore.markDone(conn, claimed, POD_A, null));
            assertEquals(EventStatus.NEW.code(), statusOf(conn, claimed));
        }
    }

    /** Claims the one pending row and returns the lease timestamp the claim wrote. */
    private Instant claimAs(Connection conn, String owner, Instant now) {
        assertEquals(1, outboxStore.claimPending(
                conn, owner, now, now.minus(Duration.ofMinutes(5)), Duration.ZERO, 10).size());
        return outboxStore.leaseTimestamp(now);
    }

    private String lockedByOf(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT locked_by FROM outbox_event WHERE status <> "
                + EventStatus.DONE.code());
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private int statusOf(Connection conn, String eventId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM outbox_event WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
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

    // ── Schema verification ─────────────────────────────────────────

    private void createTimestampTable(String table, String createdAt, String lockedAt) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("CREATE TABLE " + table + " (event_id VARCHAR(36) PRIMARY KEY, "
                    + "created_at " + createdAt + " NOT NULL, locked_at " + lockedAt + ")");
        }
    }

    private void verify(String table) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            new H2OutboxStore(table).verifySchema(conn);
        }
    }

    @Test
    void verifySchemaAcceptsTheShippedColumns() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            outboxStore.verifySchema(conn);
        }
    }

    @Test
    void verifySchemaAcceptsMillisecondColumns() throws SQLException {
        createTimestampTable("ms_outbox", "TIMESTAMP(3)", "TIMESTAMP(3)");
        verify("ms_outbox");
    }

    /** A whole-second locked_at rounds every lease, so no fenced write or release ever matches. */
    @Test
    void verifySchemaRefusesAWholeSecondLockedAt() throws SQLException {
        createTimestampTable("coarse_outbox", "TIMESTAMP(0)", "TIMESTAMP(0)");
        var e = assertThrows(IllegalStateException.class, () -> verify("coarse_outbox"));
        assertTrue(e.getMessage().contains("locked_at"), e.getMessage());
    }

    /** Both hold milliseconds, but a stamp written to both never compares equal across precisions. */
    @Test
    void verifySchemaRefusesCreatedAtAndLockedAtAtDifferentPrecisions() throws SQLException {
        createTimestampTable("mixed_outbox", "TIMESTAMP(6)", "TIMESTAMP(3)");
        var e = assertThrows(IllegalStateException.class, () -> verify("mixed_outbox"));
        assertTrue(e.getMessage().contains("differ"), e.getMessage());
    }

    /**
     * getColumns takes the schema as a LIKE pattern: "tenant_a" also matches "tenantza". Only the
     * table in the connection's own schema — the one the store's SQL resolves to — is checked.
     */
    @Test
    void verifySchemaMatchesTheConnectionsSchemaLiterally() throws SQLException {
        try (Connection conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE SCHEMA tenant_a");
            st.execute("CREATE SCHEMA tenantza");
            st.execute("CREATE TABLE tenant_a.outbox_event (event_id VARCHAR(36) PRIMARY KEY, "
                    + "created_at TIMESTAMP(6) NOT NULL, locked_at TIMESTAMP(6))");
            st.execute("CREATE TABLE tenantza.outbox_event (event_id VARCHAR(36) PRIMARY KEY, "
                    + "created_at TIMESTAMP(0) NOT NULL, locked_at TIMESTAMP(0))");

            conn.setSchema("TENANT_A");
            new H2OutboxStore().verifySchema(conn);

            // The other way round, a coarse table in use beside a fine neighbour must be refused —
            // not matched together with the neighbour and skipped as ambiguous.
            st.execute("CREATE SCHEMA tenant_b");
            st.execute("CREATE SCHEMA tenantzb");
            st.execute("CREATE TABLE tenant_b.outbox_event (event_id VARCHAR(36) PRIMARY KEY, "
                    + "created_at TIMESTAMP(0) NOT NULL, locked_at TIMESTAMP(0))");
            st.execute("CREATE TABLE tenantzb.outbox_event (event_id VARCHAR(36) PRIMARY KEY, "
                    + "created_at TIMESTAMP(6) NOT NULL, locked_at TIMESTAMP(6))");
            conn.setSchema("TENANT_B");
            assertThrows(IllegalStateException.class, () -> new H2OutboxStore().verifySchema(conn));
        }
    }

    /**
     * The store's SQL names the table unquoted, which H2 folds to upper case. A quoted lower-case
     * look-alike is a different table and must not stand in for it, either way round.
     */
    @Test
    void verifySchemaChecksTheTableTheUnquotedNameResolvesTo() throws SQLException {
        createTimestampTable("outbox_shadow", "TIMESTAMP(6)", "TIMESTAMP(6)");
        createTimestampTable("\"outbox_shadow\"", "TIMESTAMP(0)", "TIMESTAMP(0)");
        verify("outbox_shadow");

        createTimestampTable("outbox_shadow2", "TIMESTAMP(0)", "TIMESTAMP(0)");
        createTimestampTable("\"outbox_shadow2\"", "TIMESTAMP(6)", "TIMESTAMP(6)");
        assertThrows(IllegalStateException.class, () -> verify("outbox_shadow2"));
    }

    /** Not found is not a mismatch: the table may live elsewhere or be created after startup. */
    @Test
    void verifySchemaSkipsATableItCannotFind() throws SQLException {
        verify("no_such_outbox");
    }

    /** The name is a LIKE pattern to getColumns; its underscore must not match another table. */
    @Test
    void verifySchemaDoesNotMistakeALookalikeTable() throws SQLException {
        createTimestampTable("abcXdef", "TIMESTAMP(0)", "TIMESTAMP(0)");
        verify("abc_def");
    }

    /** A stamp is the owner plus this run's token: {@code owner#<22 base64url chars>}. */
    private static void assertStampOf(String owner, String lockedBy, String message) {
        assertNotNull(lockedBy, message);
        assertTrue(lockedBy.startsWith(owner + "#"), message + ": " + lockedBy);
        assertEquals(owner.length() + 23, lockedBy.length(), message + ": " + lockedBy);
        assertTrue(lockedBy.substring(owner.length() + 1).matches("[A-Za-z0-9_-]{22}"), message + ": " + lockedBy);
    }
}
