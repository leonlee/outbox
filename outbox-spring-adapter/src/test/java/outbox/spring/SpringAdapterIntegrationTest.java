package outbox.spring;

import outbox.OutboxWriter;
import outbox.registry.DefaultListenerRegistry;
import outbox.dispatch.OutboxDispatcher;
import outbox.EventEnvelope;
import outbox.dispatch.ExponentialBackoffRetryPolicy;
import outbox.model.EventStatus;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.H2EventStore;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAdapterIntegrationTest {
  private DataSource dataSource;
  private H2EventStore eventStore;
  private DataSourceConnectionProvider connectionProvider;
  private SpringTxContext txContext;
  private DataSourceTransactionManager txManager;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_spring_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.eventStore = new H2EventStore();
    this.connectionProvider = new DataSourceConnectionProvider(ds);
    this.txContext = new SpringTxContext(ds);
    this.txManager = new DataSourceTransactionManager(ds);

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
  void commitTriggersFastPathAndMarksDone() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .register("UserCreated", event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, listeners);
    OutboxWriter client = new OutboxWriter(txContext, eventStore, dispatcher);

    TransactionStatus status = txManager.getTransaction(new DefaultTransactionDefinition());
    String eventId;
    try {
      eventId = client.write(EventEnvelope.ofJson("UserCreated", "{\"id\":42}"));
      txManager.commit(status);
    } catch (RuntimeException ex) {
      txManager.rollback(status);
      throw ex;
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    awaitStatus(eventId, EventStatus.DONE, 2_000);

    dispatcher.close();
  }

  @Test
  void rollbackDoesNotPersistAndDoesNotEnqueue() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry listeners = new DefaultListenerRegistry()
        .register("UserCreated", event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, listeners);
    OutboxWriter client = new OutboxWriter(txContext, eventStore, dispatcher);

    TransactionStatus status = txManager.getTransaction(new DefaultTransactionDefinition());
    String eventId;
    try {
      eventId = client.write(EventEnvelope.ofJson("UserCreated", "{\"id\":99}"));
      txManager.rollback(status);
    } catch (RuntimeException ex) {
      txManager.rollback(status);
      throw ex;
    }

    assertFalse(latch.await(200, TimeUnit.MILLISECONDS));
    assertEquals(-1, getStatus(eventId));

    dispatcher.close();
  }

  private OutboxDispatcher dispatcher(int workers, int hotCapacity, int coldCapacity, DefaultListenerRegistry listeners) {
    return OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
        .listenerRegistry(listeners)
        .retryPolicy(new ExponentialBackoffRetryPolicy(10, 50))
        .workerCount(workers)
        .hotQueueCapacity(hotCapacity)
        .coldQueueCapacity(coldCapacity)
        .build();
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
