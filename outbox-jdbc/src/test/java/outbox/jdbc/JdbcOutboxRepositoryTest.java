package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import outbox.core.api.EventEnvelope;
import outbox.core.api.OutboxStatus;
import outbox.core.repo.OutboxRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcOutboxRepositoryTest {

  private JdbcDataSource dataSource;
  private JdbcOutboxRepository repository;

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
              "last_error CLOB" +
              ")"
      );
    }

    repository = new JdbcOutboxRepository();
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
        assertEquals(OutboxStatus.NEW.code(), rs.getInt("status"));
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
        assertEquals(OutboxStatus.DONE.code(), rs.getInt("status"));
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
        assertEquals(OutboxStatus.RETRY.code(), rs.getInt("status"));
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
        assertEquals(OutboxStatus.DEAD.code(), rs.getInt("status"));
        assertEquals("Max retries exceeded", rs.getString("last_error"));
      }
    }
  }

  @Test
  void pollPendingReturnsNewEvents() throws SQLException {
    String eventId = insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      List<OutboxRow> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
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

      List<OutboxRow> rows = repository.pollPending(conn, Instant.now(),
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

      List<OutboxRow> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
          Duration.ZERO, 10);

      assertTrue(rows.isEmpty());
    }
  }

  @Test
  void pollPendingExcludesDeadEvents() throws SQLException {
    String eventId = insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      repository.markDead(conn, eventId, "dead");

      List<OutboxRow> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
          Duration.ZERO, 10);

      assertTrue(rows.isEmpty());
    }
  }

  @Test
  void pollPendingRespectsSkipRecent() throws SQLException {
    insertTestEvent();

    try (Connection conn = dataSource.getConnection()) {
      // Skip events created in the last hour
      List<OutboxRow> rows = repository.pollPending(conn, Instant.now(),
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
      List<OutboxRow> rows = repository.pollPending(conn, Instant.now().plusSeconds(1),
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

      List<OutboxRow> rows = repository.pollPending(conn, Instant.now(),
          Duration.ZERO, 10);

      assertTrue(rows.isEmpty());
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
