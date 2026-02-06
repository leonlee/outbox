package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.model.EventStatus;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import outbox.jdbc.dialect.Dialects;

import static org.junit.jupiter.api.Assertions.*;

class JdbcEventStoreTest {

  private JdbcDataSource dataSource;
  private JdbcEventStore repository;

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

    repository = new JdbcEventStore(Dialects.get("h2"));
  }

  @Test
  void insertNewCreatesRowWithCorrectStatus() throws SQLException {
    EventEnvelope event = EventEnvelope.builder("TestEvent")
        .aggregateType("Test")
        .aggregateId("123")
        .payloadJson("{\"test\":true}")
        .build();

    try (Connection conn = dataSource.getConnection()) {
      repository.insertNew(conn, event);

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
      repository.insertNew(conn, event);

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
      int updated = repository.markDone(conn, eventId);

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
      int first = repository.markDone(conn, eventId);
      int second = repository.markDone(conn, eventId);

      assertEquals(1, first);
      assertEquals(0, second); // Already DONE, no update
    }
  }

  @Test
  void markRetryUpdatesStatusAndIncrementsAttempts() throws SQLException {
    String eventId = insertTestEvent();
    Instant nextAt = Instant.now().plusSeconds(60);

    try (Connection conn = dataSource.getConnection()) {
      int updated = repository.markRetry(conn, eventId, nextAt, "Connection failed");

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

  @Test
  void markRetryTruncatesLongError() throws SQLException {
    String eventId = insertTestEvent();
    StringBuilder longError = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      longError.append("x");
    }

    try (Connection conn = dataSource.getConnection()) {
      repository.markRetry(conn, eventId, Instant.now(), longError.toString());

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
      int updated = repository.markDead(conn, eventId, "Max retries exceeded");

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
      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
          Duration.ZERO, 10);

      assertEquals(1, rows.size());
      assertEquals(eventId, rows.get(0).eventId());
    }
  }

  @Test
  void pollPendingReturnsRetryEvents() throws SQLException {
    String eventId = insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      repository.markRetry(conn, eventId, Instant.now().minusSeconds(10), "error");

      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now(),
          Duration.ZERO, 10);

      assertEquals(1, rows.size());
      assertEquals(eventId, rows.get(0).eventId());
    }
  }

  @Test
  void pollPendingExcludesDoneEvents() throws SQLException {
    String eventId = insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      repository.markDone(conn, eventId);

      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
          Duration.ZERO, 10);

      assertTrue(rows.isEmpty());
    }
  }

  @Test
  void pollPendingExcludesDeadEvents() throws SQLException {
    String eventId = insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      repository.markDead(conn, eventId, "dead");

      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
          Duration.ZERO, 10);

      assertTrue(rows.isEmpty());
    }
  }

  @Test
  void pollPendingRespectsSkipRecent() throws SQLException {
    insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      // Skip events created in the last hour
      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now(),
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
      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
          Duration.ZERO, 3);

      assertEquals(3, rows.size());
    }
  }

  @Test
  void pollPendingRespectsAvailableAt() throws SQLException {
    String eventId = insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      // Set available_at to future
      repository.markRetry(conn, eventId, Instant.now().plus(Duration.ofHours(1)), "delayed");

      List<OutboxEvent> rows = repository.pollPending(conn, Instant.now(),
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
      List<OutboxEvent> claimed = repository.claimPending(
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
      List<OutboxEvent> claimedA = repository.claimPending(
          conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);
      assertEquals(2, claimedA.size());

      // owner-B should get 0 because all are locked by owner-A with non-expired locks
      List<OutboxEvent> claimedB = repository.claimPending(
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
      List<OutboxEvent> claimedA = repository.claimPending(
          conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);
      assertEquals(1, claimedA.size());

      // Simulate expired lock by setting locked_at to the past
      conn.createStatement().execute(
          "UPDATE outbox_event SET locked_at = TIMESTAMP '2020-01-01 00:00:00'");

      // owner-B should reclaim the expired lock
      List<OutboxEvent> claimedB = repository.claimPending(
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
      repository.claimPending(conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);

      repository.markDone(conn, eventId);

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
      repository.claimPending(conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);

      repository.markRetry(conn, eventId, Instant.now().plusSeconds(60), "retry error");

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
      repository.claimPending(conn, "owner-A", now, lockExpiry, Duration.ZERO, 10);

      repository.markDead(conn, eventId, "dead");

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

  private String insertTestEvent() throws SQLException {
    EventEnvelope event = EventEnvelope.builder("TestEvent")
        .payloadJson("{}")
        .build();

    try (Connection conn = dataSource.getConnection()) {
      repository.insertNew(conn, event);
    }

    return event.eventId();
  }
}
