package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import outbox.jdbc.purge.H2EventPurger;
import outbox.model.EventStatus;
import outbox.purge.OutboxPurgeScheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxPurgeSchedulerTest {

  private JdbcDataSource dataSource;

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
  }

  @Test
  void schedulerPurgesOnRunOnce() throws SQLException {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    insertEvent("done-1", EventStatus.DONE, old, old);
    insertEvent("new-1", EventStatus.NEW, old, null);

    try (OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
        .connectionProvider(dataSource::getConnection)
        .purger(new H2EventPurger())
        .retention(Duration.ofDays(7))
        .batchSize(500)
        .intervalSeconds(3600)
        .build()) {
      scheduler.runOnce();

      assertEquals(1, countAll());
      assertTrue(eventExists("new-1"));
      assertFalse(eventExists("done-1"));
    }
  }

  @Test
  void schedulerDrainsLargeBacklog() throws SQLException {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    for (int i = 0; i < 1500; i++) {
      insertEvent("done-" + i, EventStatus.DONE, old, old);
    }

    try (OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
        .connectionProvider(dataSource::getConnection)
        .purger(new H2EventPurger())
        .retention(Duration.ofDays(7))
        .batchSize(500)
        .intervalSeconds(3600)
        .build()) {
      scheduler.runOnce();

      assertEquals(0, countAll());
    }
  }

  @Test
  void schedulerStartAndClose() throws Exception {
    Instant old = Instant.now().minus(10, ChronoUnit.DAYS);
    insertEvent("done-1", EventStatus.DONE, old, old);

    OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
        .connectionProvider(dataSource::getConnection)
        .purger(new H2EventPurger())
        .retention(Duration.ofDays(7))
        .batchSize(500)
        .intervalSeconds(1)
        .build();

    scheduler.start();
    // start is idempotent
    scheduler.start();
    // Give the scheduler time to run at least once
    Thread.sleep(2000);
    scheduler.close();

    assertEquals(0, countAll());
  }

  @Test
  void schedulerStartAfterCloseThrows() throws Exception {
    OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
        .connectionProvider(dataSource::getConnection)
        .purger(new H2EventPurger())
        .retention(Duration.ofDays(7))
        .batchSize(500)
        .intervalSeconds(1)
        .build();
    scheduler.close();

    IllegalStateException ex = assertThrows(IllegalStateException.class, scheduler::start);
    assertTrue(ex.getMessage().contains("has been closed"));
  }

  @Test
  void schedulerBuilderValidation() {
    assertThrows(NullPointerException.class, () ->
        OutboxPurgeScheduler.builder()
            .purger(new H2EventPurger())
            .intervalSeconds(3600)
            .build());

    assertThrows(NullPointerException.class, () ->
        OutboxPurgeScheduler.builder()
            .connectionProvider(dataSource::getConnection)
            .intervalSeconds(3600)
            .build());

    assertThrows(IllegalArgumentException.class, () ->
        OutboxPurgeScheduler.builder()
            .connectionProvider(dataSource::getConnection)
            .purger(new H2EventPurger())
            .batchSize(0)
            .build());

    assertThrows(IllegalArgumentException.class, () ->
        OutboxPurgeScheduler.builder()
            .connectionProvider(dataSource::getConnection)
            .purger(new H2EventPurger())
            .intervalSeconds(0)
            .build());

    assertThrows(IllegalArgumentException.class, () ->
        OutboxPurgeScheduler.builder()
            .connectionProvider(dataSource::getConnection)
            .purger(new H2EventPurger())
            .retention(Duration.ofDays(-1))
            .build());
  }

  private void insertEvent(String eventId, EventStatus status,
      Instant createdAt, Instant doneAt) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      String sql = "INSERT INTO outbox_event (" +
          "event_id, event_type, payload, status, attempts, available_at, created_at, done_at" +
          ") VALUES (?,?,?,?,?,?,?,?)";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, eventId);
        ps.setString(2, "TestEvent");
        ps.setString(3, "{}");
        ps.setInt(4, status.code());
        ps.setInt(5, 0);
        ps.setTimestamp(6, Timestamp.from(createdAt));
        ps.setTimestamp(7, Timestamp.from(createdAt));
        ps.setTimestamp(8, doneAt != null ? Timestamp.from(doneAt) : null);
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
