package outbox.jdbc;

import outbox.dispatch.DefaultInFlightTracker;
import outbox.OutboxClient;
import outbox.registry.DefaultListenerRegistry;
import outbox.dispatch.OutboxDispatcher;
import outbox.EventEnvelope;
import outbox.dispatch.ExponentialBackoffRetryPolicy;
import outbox.spi.MetricsExporter;
import outbox.poller.OutboxPoller;
import outbox.model.EventStatus;
import outbox.dispatch.QueuedEvent;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxAcceptanceTest {
  private DataSource dataSource;
  private JdbcOutboxRepository repository;
  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.repository = new JdbcOutboxRepository();
    this.connectionProvider = new DataSourceConnectionProvider(ds);
    this.txContext = new ThreadLocalTxContext();
    this.txManager = new JdbcTransactionManager(connectionProvider, txContext);

    try (Connection conn = ds.getConnection()) {
      createSchema(conn);
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute("DROP TABLE outbox_event");
    }
  }

  @Test
  void atomicityRollbackDoesNotPersist() throws Exception {
    OutboxDispatcher dispatcher = dispatcher(0, 10, 10);
    OutboxClient client = new OutboxClient(txContext, repository, dispatcher, MetricsExporter.NOOP);

    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      client.publish(EventEnvelope.ofJson("TestEvent", "{}"));
      tx.rollback();
    }

    assertEquals(0, countRows());
    dispatcher.close();
  }

  @Test
  void commitFastPathPublishesAndMarksDone() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry publishers = new DefaultListenerRegistry()
        .registerAll(event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, publishers);
    OutboxClient client = new OutboxClient(txContext, repository, dispatcher, MetricsExporter.NOOP);

    String eventId;
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      eventId = client.publish(EventEnvelope.ofJson("UserCreated", "{\"id\":1}"));
      tx.commit();
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    awaitStatus(eventId, EventStatus.DONE, 2_000);

    dispatcher.close();
  }

  @Test
  void queueOverflowDowngradesToPoller() throws Exception {
    OutboxDispatcher noWorkers = dispatcher(0, 1, 1);
    noWorkers.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("Preload", "{}"), QueuedEvent.Source.HOT, 0));

    OutboxClient client = new OutboxClient(txContext, repository, noWorkers, MetricsExporter.NOOP);

    String eventId;
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      eventId = client.publish(EventEnvelope.ofJson("Overflow", "{}"));
      tx.commit();
    }

    assertEquals(EventStatus.NEW.code(), getStatus(eventId));

    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry publishers = new DefaultListenerRegistry()
        .registerAll(event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, publishers);
    try (OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ofMillis(0),
        10,
        10,
        MetricsExporter.NOOP
    )) {
      for (int i = 0; i < 20 && getStatus(eventId) != EventStatus.DONE.code(); i++) {
        poller.poll();
        Thread.sleep(25);
      }
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    awaitStatus(eventId, EventStatus.DONE, 2_000);

    dispatcher.close();
    noWorkers.close();
  }

  @Test
  void retryThenDeadAfterMaxAttempts() throws Exception {
    DefaultListenerRegistry publishers = new DefaultListenerRegistry()
        .registerAll(event -> { throw new RuntimeException("boom"); });

    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, publishers, 3);
    OutboxClient client = new OutboxClient(txContext, repository, dispatcher, MetricsExporter.NOOP);

    String eventId;
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      eventId = client.publish(EventEnvelope.ofJson("Failing", "{}"));
      tx.commit();
    }

    try (OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ofMillis(0),
        10,
        10,
        MetricsExporter.NOOP
    )) {
      long deadline = System.currentTimeMillis() + 2_000;
      while (System.currentTimeMillis() < deadline && getStatus(eventId) != EventStatus.DEAD.code()) {
        poller.poll();
        Thread.sleep(30);
      }
    }

    assertEquals(EventStatus.DEAD.code(), getStatus(eventId));
    assertNotNull(getLastError(eventId));

    dispatcher.close();
  }

  private OutboxDispatcher dispatcher(int workers, int hotCapacity, int coldCapacity) {
    return dispatcher(workers, hotCapacity, coldCapacity, new DefaultListenerRegistry());
  }

  private OutboxDispatcher dispatcher(int workers, int hotCapacity, int coldCapacity, DefaultListenerRegistry listeners) {
    return dispatcher(workers, hotCapacity, coldCapacity, listeners, 10);
  }

  private OutboxDispatcher dispatcher(int workers, int hotCapacity, int coldCapacity, DefaultListenerRegistry listeners, int maxAttempts) {
    return new OutboxDispatcher(
        connectionProvider,
        repository,
        listeners,
        new DefaultInFlightTracker(),
        new ExponentialBackoffRetryPolicy(10, 50),
        maxAttempts,
        workers,
        hotCapacity,
        coldCapacity,
        MetricsExporter.NOOP
    );
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

  private int countRows() throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM outbox_event");
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
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

  private void awaitStatus(String eventId, EventStatus status, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (getStatus(eventId) == status.code()) {
        return;
      }
      Thread.sleep(20);
    }
    assertEquals(status.code(), getStatus(eventId));
  }
}
