package outbox.jdbc;

import outbox.registry.DefaultListenerRegistry;
import outbox.dispatch.OutboxDispatcher;
import outbox.dispatch.ExponentialBackoffRetryPolicy;
import outbox.spi.MetricsExporter;
import outbox.poller.OutboxPoller;
import outbox.model.EventStatus;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import outbox.jdbc.dialect.Dialects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxEdgeCaseTest {
  private DataSource dataSource;
  private JdbcEventStore eventStore;
  private DataSourceConnectionProvider connectionProvider;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_edge_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.eventStore = new JdbcEventStore(Dialects.get("h2"));
    this.connectionProvider = new DataSourceConnectionProvider(ds);

    try (Connection conn = ds.getConnection()) {
      createSchema(conn);
    }
  }

  @Test
  void pollerMarksDeadWhenHeadersMalformed() throws Exception {
    String eventId = "evt_bad_headers";
    try (Connection conn = dataSource.getConnection()) {
      String sql = "INSERT INTO outbox_event (event_id, event_type, payload, headers, status, attempts, " +
          "available_at, created_at, done_at, last_error) VALUES (?,?,?,?,?,?,?,?,NULL,NULL)";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, eventId);
        ps.setString(2, "BadHeaders");
        ps.setString(3, "{}");
        ps.setString(4, "not-json");
        ps.setInt(5, EventStatus.NEW.code());
        ps.setInt(6, 0);
        Timestamp now = Timestamp.from(Instant.now().minusSeconds(5));
        ps.setTimestamp(7, now);
        ps.setTimestamp(8, now);
        ps.executeUpdate();
      }
    }

    OutboxDispatcher dispatcher = OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
        .listenerRegistry(new DefaultListenerRegistry())
        .retryPolicy(new ExponentialBackoffRetryPolicy(10, 50))
        .workerCount(1)
        .build();

    try (OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        eventStore,
        dispatcher,
        Duration.ofMillis(0),
        10,
        10,
        MetricsExporter.NOOP
    )) {
      poller.poll();
    }

    assertEquals(EventStatus.DEAD.code(), getStatus(eventId));
    assertNotNull(getLastError(eventId));

    dispatcher.close();
  }

  @Test
  void dispatcherRejectsInvalidArgs() {
    assertThrows(IllegalArgumentException.class, () ->
        OutboxDispatcher.builder()
            .connectionProvider(connectionProvider)
            .eventStore(eventStore)
            .listenerRegistry(new DefaultListenerRegistry())
            .maxAttempts(0)
            .build());

    assertThrows(IllegalArgumentException.class, () ->
        OutboxDispatcher.builder()
            .connectionProvider(connectionProvider)
            .eventStore(eventStore)
            .listenerRegistry(new DefaultListenerRegistry())
            .workerCount(-1)
            .build());

    assertThrows(IllegalArgumentException.class, () ->
        OutboxDispatcher.builder()
            .connectionProvider(connectionProvider)
            .eventStore(eventStore)
            .listenerRegistry(new DefaultListenerRegistry())
            .hotQueueCapacity(0)
            .build());
  }

  @Test
  void pollerRejectsInvalidArgs() {
    OutboxDispatcher dispatcher = OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
        .listenerRegistry(new DefaultListenerRegistry())
        .retryPolicy(new ExponentialBackoffRetryPolicy(10, 50))
        .workerCount(1)
        .build();

    assertThrows(IllegalArgumentException.class, () -> new OutboxPoller(
        connectionProvider,
        eventStore,
        dispatcher,
        Duration.ofMillis(0),
        0,
        10,
        MetricsExporter.NOOP
    ));

    assertThrows(IllegalArgumentException.class, () -> new OutboxPoller(
        connectionProvider,
        eventStore,
        dispatcher,
        Duration.ofMillis(0),
        10,
        0,
        MetricsExporter.NOOP
    ));

    assertThrows(IllegalArgumentException.class, () -> new OutboxPoller(
        connectionProvider,
        eventStore,
        dispatcher,
        Duration.ofMillis(-1),
        10,
        10,
        MetricsExporter.NOOP
    ));

    dispatcher.close();
  }

  private void createSchema(Connection conn) throws SQLException {
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
    conn.createStatement().execute(
        "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)"
    );
  }

  private int getStatus(String eventId) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT status FROM outbox_event WHERE event_id=?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return -1;
        }
        return rs.getInt(1);
      }
    }
  }

  private String getLastError(String eventId) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT last_error FROM outbox_event WHERE event_id=?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return rs.getString(1);
      }
    }
  }
}
