package outbox.jdbc;

import outbox.EventEnvelope;
import outbox.Outbox;
import outbox.OutboxWriter;
import outbox.jdbc.purge.H2AgeBasedPurger;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;
import outbox.model.EventStatus;
import outbox.registry.DefaultListenerRegistry;

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

class OutboxCompositeTest {
  private DataSource dataSource;
  private H2OutboxStore outboxStore;
  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.outboxStore = new H2OutboxStore();
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
  void singleNode_hotPathDispatch() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("UserCreated", event -> latch.countDown());

    try (Outbox outbox = Outbox.singleNode()
        .connectionProvider(connectionProvider)
        .txContext(txContext)
        .outboxStore(outboxStore)
        .listenerRegistry(registry)
        .intervalMs(60_000)
        .build()) {

      OutboxWriter writer = outbox.writer();
      String eventId;
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        eventId = writer.write(EventEnvelope.ofJson("UserCreated", "{\"id\":1}"));
        tx.commit();
      }

      assertTrue(latch.await(2, TimeUnit.SECONDS), "Listener should be invoked via hot path");
      awaitStatus(eventId, EventStatus.DONE, 2_000);
    }
  }

  @Test
  void ordered_pollerOnlyDispatch() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("OrderPlaced", event -> latch.countDown());

    try (Outbox outbox = Outbox.ordered()
        .connectionProvider(connectionProvider)
        .txContext(txContext)
        .outboxStore(outboxStore)
        .listenerRegistry(registry)
        .intervalMs(50)
        .batchSize(10)
        .build()) {

      OutboxWriter writer = outbox.writer();
      String eventId;
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        eventId = writer.write(EventEnvelope.ofJson("OrderPlaced", "{\"orderId\":\"o-1\"}"));
        tx.commit();
      }

      assertTrue(latch.await(3, TimeUnit.SECONDS), "Listener should be invoked via poller");
      awaitStatus(eventId, EventStatus.DONE, 2_000);
    }
  }

  @Test
  void ordered_noRetry_failedEventMarksDead() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("Failing", event -> { throw new RuntimeException("boom"); });

    try (Outbox outbox = Outbox.ordered()
        .connectionProvider(connectionProvider)
        .txContext(txContext)
        .outboxStore(outboxStore)
        .listenerRegistry(registry)
        .intervalMs(50)
        .batchSize(10)
        .build()) {

      OutboxWriter writer = outbox.writer();
      String eventId;
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        eventId = writer.write(EventEnvelope.ofJson("Failing", "{}"));
        tx.commit();
      }

      awaitStatus(eventId, EventStatus.DEAD, 3_000);
      assertNotNull(getLastError(eventId));
    }
  }

  @Test
  void multiNode_claimLockingDispatch() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("Claimed", event -> latch.countDown());

    try (Outbox outbox = Outbox.multiNode()
        .connectionProvider(connectionProvider)
        .txContext(txContext)
        .outboxStore(outboxStore)
        .listenerRegistry(registry)
        .claimLocking("test-node", Duration.ofMinutes(5))
        .intervalMs(50)
        .batchSize(10)
        .build()) {

      OutboxWriter writer = outbox.writer();
      String eventId;
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        eventId = writer.write(EventEnvelope.ofJson("Claimed", "{\"id\":1}"));
        tx.commit();
      }

      assertTrue(latch.await(3, TimeUnit.SECONDS), "Listener should be invoked");
      awaitStatus(eventId, EventStatus.DONE, 2_000);
    }
  }

  @Test
  void writerOnly_writesEventsWithoutDispatch() throws Exception {
    try (Outbox outbox = Outbox.writerOnly()
        .txContext(txContext)
        .outboxStore(outboxStore)
        .build()) {

      OutboxWriter writer = outbox.writer();
      String eventId;
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        eventId = writer.write(EventEnvelope.ofJson("CdcEvent", "{\"id\":1}"));
        tx.commit();
      }

      // Event stays in NEW status — no dispatcher/poller to process it
      assertEquals(EventStatus.NEW.code(), getStatus(eventId));
    }
  }

  @Test
  void writerOnly_withPurge_buildsAndClosesCleanly() throws Exception {
    try (Outbox outbox = Outbox.writerOnly()
        .txContext(txContext)
        .outboxStore(outboxStore)
        .connectionProvider(connectionProvider)
        .purger(new H2AgeBasedPurger())
        .purgeIntervalSeconds(3600)
        .build()) {
      assertNotNull(outbox.writer());
    }
  }

  @Test
  void singleNode_closeShutdownCleanly() throws Exception {
    DefaultListenerRegistry registry = new DefaultListenerRegistry();

    Outbox outbox = Outbox.singleNode()
        .connectionProvider(connectionProvider)
        .txContext(txContext)
        .outboxStore(outboxStore)
        .listenerRegistry(registry)
        .intervalMs(60_000)
        .drainTimeoutMs(500)
        .build();

    assertNotNull(outbox.writer());
    outbox.close(); // should not throw
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
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  private String getLastError(String eventId) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT last_error FROM outbox_event WHERE event_id=?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
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
