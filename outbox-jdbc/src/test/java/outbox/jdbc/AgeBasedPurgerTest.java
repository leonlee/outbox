package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import outbox.jdbc.purge.H2AgeBasedPurger;
import outbox.model.EventStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgeBasedPurgerTest {

  private JdbcDataSource dataSource;
  private H2AgeBasedPurger purger;

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

    purger = new H2AgeBasedPurger();
  }

  @Test
  void purgeDeletesAllOldEventsRegardlessOfStatus() throws SQLException {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    insertEvent("new-1", EventStatus.NEW, old);
    insertEvent("retry-1", EventStatus.RETRY, old);
    insertEvent("done-1", EventStatus.DONE, old);
    insertEvent("dead-1", EventStatus.DEAD, old);

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      int deleted = purger.purge(conn, Instant.now().minus(1, ChronoUnit.DAYS), 1000);

      assertEquals(4, deleted);
      assertEquals(0, countAll());
    }
  }

  @Test
  void purgeRespectsAgeCutoff() throws SQLException {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
    insertEvent("old-new", EventStatus.NEW, old);
    insertEvent("recent-new", EventStatus.NEW, recent);

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      int deleted = purger.purge(conn, Instant.now().minus(1, ChronoUnit.DAYS), 1000);

      assertEquals(1, deleted);
      assertFalse(eventExists("old-new"));
      assertTrue(eventExists("recent-new"));
    }
  }

  @Test
  void purgeRespectsLimit() throws SQLException {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    for (int i = 0; i < 5; i++) {
      insertEvent("evt-" + i, EventStatus.NEW, old);
    }

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      int deleted = purger.purge(conn, Instant.now(), 3);

      assertEquals(3, deleted);
      assertEquals(2, countAll());
    }
  }

  @Test
  void purgeReturnsCorrectCount() throws SQLException {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    insertEvent("evt-1", EventStatus.NEW, old);
    insertEvent("evt-2", EventStatus.DONE, old);

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      int deleted = purger.purge(conn, Instant.now(), 1000);

      assertEquals(2, deleted);
    }
  }

  @Test
  void purgeWithNoMatchingEvents() throws SQLException {
    Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
    insertEvent("recent-1", EventStatus.NEW, recent);

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      int deleted = purger.purge(conn, Instant.now().minus(1, ChronoUnit.DAYS), 1000);

      assertEquals(0, deleted);
      assertEquals(1, countAll());
    }
  }

  @Test
  void customTableName() throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute(
          "CREATE TABLE custom_outbox (" +
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

    H2AgeBasedPurger customPurger = new H2AgeBasedPurger("custom_outbox");
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    insertEventInTable("custom_outbox", "new-1", EventStatus.NEW, old);

    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(true);
      int deleted = customPurger.purge(conn, Instant.now(), 1000);
      assertEquals(1, deleted);
    }
  }

  @Test
  void tableNameValidation() {
    assertThrows(IllegalArgumentException.class, () -> new H2AgeBasedPurger("bad table!"));
    assertThrows(IllegalArgumentException.class, () -> new H2AgeBasedPurger("123start"));
    assertThrows(NullPointerException.class, () -> new H2AgeBasedPurger(null));
    assertDoesNotThrow(() -> new H2AgeBasedPurger("my_events"));
    assertDoesNotThrow(() -> new H2AgeBasedPurger("_private"));
  }

  private void insertEvent(String eventId, EventStatus status, Instant createdAt) throws SQLException {
    insertEventInTable("outbox_event", eventId, status, createdAt);
  }

  private void insertEventInTable(String table, String eventId, EventStatus status,
      Instant createdAt) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      String sql = "INSERT INTO " + table + " (" +
          "event_id, event_type, payload, status, attempts, available_at, created_at" +
          ") VALUES (?,?,?,?,?,?,?)";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, eventId);
        ps.setString(2, "TestEvent");
        ps.setString(3, "{}");
        ps.setInt(4, status.code());
        ps.setInt(5, 0);
        ps.setTimestamp(6, Timestamp.from(createdAt));
        ps.setTimestamp(7, Timestamp.from(createdAt));
        ps.executeUpdate();
      }
    }
  }

  private int countAll() throws SQLException {
    try (Connection conn = dataSource.getConnection();
         ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM outbox_event")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private boolean eventExists(String eventId) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
             "SELECT 1 FROM outbox_event WHERE event_id = ?")) {
      ps.setString(1, eventId);
      return ps.executeQuery().next();
    }
  }
}
