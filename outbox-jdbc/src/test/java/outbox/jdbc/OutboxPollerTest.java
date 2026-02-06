package outbox.jdbc;

import outbox.EventEnvelope;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.OutboxDispatcher;
import outbox.model.EventStatus;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.MetricsExporter;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import outbox.jdbc.dialect.Dialects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OutboxPollerTest {
  private DataSource dataSource;
  private JdbcOutboxRepository repository;
  private DataSourceConnectionProvider connectionProvider;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_poller_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.repository = new JdbcOutboxRepository(Dialects.get("h2"));
    this.connectionProvider = new DataSourceConnectionProvider(ds);

    try (Connection conn = ds.getConnection()) {
      createSchema(conn);
    }
  }

  @AfterEach
  void teardown() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute("DROP TABLE outbox_event");
    }
  }

  @Test
  void pollerSkipsRecentEvents() throws Exception {
    EventEnvelope event = EventEnvelope.builder("Recent")
        .eventId("evt-recent")
        .occurredAt(Instant.now())
        .payloadJson("{}").build();
    insertEvent(event);

    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .register("Recent", e -> latch.countDown());

    OutboxDispatcher dispatcher = new OutboxDispatcher(
        connectionProvider,
        repository,
        listeners,
        new DefaultInFlightTracker(),
        attempts -> 0L,
        10,
        1,
        10,
        10,
        MetricsExporter.NOOP
    );

    RecordingMetrics metrics = new RecordingMetrics();
    try (OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ofHours(1),
        10,
        10,
        metrics
    )) {
      poller.poll();
    }

    assertFalse(latch.await(200, TimeUnit.MILLISECONDS));
    assertEquals(EventStatus.NEW.code(), getStatus(event.eventId()));
    assertEquals(0, metrics.coldEnqueued.get());

    dispatcher.close();
  }

  @Test
  void pollerStopsWhenColdQueueIsFull() throws Exception {
    Instant createdAt = Instant.now().minusSeconds(5);
    insertEvent(EventEnvelope.builder("Test").eventId("evt-1").occurredAt(createdAt).payloadJson("{}").build());
    insertEvent(EventEnvelope.builder("Test").eventId("evt-2").occurredAt(createdAt).payloadJson("{}").build());
    insertEvent(EventEnvelope.builder("Test").eventId("evt-3").occurredAt(createdAt).payloadJson("{}").build());

    OutboxDispatcher dispatcher = new OutboxDispatcher(
        connectionProvider,
        repository,
        new DefaultListenerRegistry(),
        new DefaultInFlightTracker(),
        attempts -> 0L,
        10,
        0,
        10,
        1,
        MetricsExporter.NOOP
    );

    RecordingMetrics metrics = new RecordingMetrics();
    try (OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ZERO,
        10,
        10,
        metrics
    )) {
      poller.poll();
    }

    assertEquals(1, metrics.coldEnqueued.get());
    assertFalse(dispatcher.hasColdQueueCapacity());

    dispatcher.close();
  }

  private void insertEvent(EventEnvelope event) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      repository.insertNew(conn, event);
    }
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
            "last_error CLOB" +
            ")"
    );
    conn.createStatement().execute(
        "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)"
    );
  }

  private static final class RecordingMetrics implements MetricsExporter {
    private final AtomicInteger coldEnqueued = new AtomicInteger();

    @Override
    public void incrementHotEnqueued() {
    }

    @Override
    public void incrementHotDropped() {
    }

    @Override
    public void incrementColdEnqueued() {
      coldEnqueued.incrementAndGet();
    }

    @Override
    public void incrementDispatchSuccess() {
    }

    @Override
    public void incrementDispatchFailure() {
    }

    @Override
    public void incrementDispatchDead() {
    }

    @Override
    public void recordQueueDepths(int hotDepth, int coldDepth) {
    }

    @Override
    public void recordOldestLagMs(long lagMs) {
    }
  }
}
