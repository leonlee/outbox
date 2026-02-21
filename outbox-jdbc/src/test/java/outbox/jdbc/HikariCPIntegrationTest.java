package outbox.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.DispatcherWriterHook;
import outbox.dispatch.OutboxDispatcher;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;
import outbox.model.EventStatus;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HikariCPIntegrationTest {
  private HikariDataSource hikariDs;
  private H2OutboxStore outboxStore;
  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;

  @BeforeEach
  void setup() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:hikari_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    config.setMaximumPoolSize(5);
    config.setMinimumIdle(1);
    config.setPoolName("outbox-test-pool");

    hikariDs = new HikariDataSource(config);
    outboxStore = new H2OutboxStore();
    connectionProvider = new DataSourceConnectionProvider(hikariDs);
    txContext = new ThreadLocalTxContext();
    txManager = new JdbcTransactionManager(connectionProvider, txContext);

    try (Connection conn = hikariDs.getConnection()) {
      createSchema(conn);
    }
  }

  @AfterEach
  void tearDown() {
    if (hikariDs != null && !hikariDs.isClosed()) {
      hikariDs.close();
    }
  }

  @Test
  void writeAndDispatchThroughPool() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("PoolEvent", event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, registry);
    OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));

    String eventId;
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      eventId = writer.write(EventEnvelope.ofJson("PoolEvent", "{\"pool\":true}"));
      tx.commit();
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    awaitStatus(eventId, EventStatus.DONE, 2_000);
    assertEquals(0, hikariDs.getHikariPoolMXBean().getActiveConnections());

    dispatcher.close();
  }

  @Test
  void noConnectionLeaksAfterMultipleWriteCycles() throws Exception {
    CountDownLatch latch = new CountDownLatch(20);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("CycleEvent", event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(2, 100, 100, registry);
    OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));

    List<String> eventIds = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        String id = writer.write(EventEnvelope.ofJson("CycleEvent", "{\"i\":" + i + "}"));
        eventIds.add(id);
        tx.commit();
      }
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    for (String id : eventIds) {
      awaitStatus(id, EventStatus.DONE, 3_000);
    }
    assertEquals(0, hikariDs.getHikariPoolMXBean().getActiveConnections());

    dispatcher.close();
  }

  @Test
  void concurrentWritersThroughPool() throws Exception {
    int threadCount = 4;
    int eventsPerThread = 10;
    int totalEvents = threadCount * eventsPerThread;

    CountDownLatch latch = new CountDownLatch(totalEvents);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("ConcurrentEvent", event -> latch.countDown());

    OutboxDispatcher dispatcher = dispatcher(2, 200, 200, registry);
    DispatcherWriterHook hook = new DispatcherWriterHook(dispatcher);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CopyOnWriteArrayList<String> allIds = new CopyOnWriteArrayList<>();

    for (int t = 0; t < threadCount; t++) {
      final int threadIdx = t;
      executor.submit(() -> {
        ThreadLocalTxContext localTxCtx = new ThreadLocalTxContext();
        JdbcTransactionManager localTxMgr = new JdbcTransactionManager(connectionProvider, localTxCtx);
        OutboxWriter localWriter = new OutboxWriter(localTxCtx, outboxStore, hook);
        for (int i = 0; i < eventsPerThread; i++) {
          try (JdbcTransactionManager.Transaction tx = localTxMgr.begin()) {
            String id = localWriter.write(
                EventEnvelope.ofJson("ConcurrentEvent", "{\"t\":" + threadIdx + ",\"i\":" + i + "}"));
            allIds.add(id);
            tx.commit();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    assertEquals(totalEvents, allIds.size());
    assertEquals(totalEvents, countRows());
    assertEquals(0, hikariDs.getHikariPoolMXBean().getActiveConnections());

    dispatcher.close();
  }

  @Test
  void pollerThroughPool() throws Exception {
    // Insert events without dispatcher (poller-only)
    OutboxWriter writer = new OutboxWriter(txContext, outboxStore);

    List<String> eventIds = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        String id = writer.write(EventEnvelope.ofJson("PollEvent", "{\"i\":" + i + "}"));
        eventIds.add(id);
        tx.commit();
      }
    }
    assertEquals(5, countRows());

    CountDownLatch latch = new CountDownLatch(5);
    DefaultListenerRegistry registry = new DefaultListenerRegistry()
        .register("PollEvent", event -> latch.countDown());
    OutboxDispatcher dispatcher = dispatcher(1, 100, 100, registry);

    try (OutboxPoller poller = OutboxPoller.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .handler(new DispatcherPollerHandler(dispatcher))
        .skipRecent(Duration.ofMillis(0))
        .batchSize(10)
        .intervalMs(10)
        .build()) {
      for (int i = 0; i < 30; i++) {
        poller.poll();
        Thread.sleep(25);
        boolean allDone = true;
        for (String id : eventIds) {
          if (getStatus(id) != EventStatus.DONE.code()) {
            allDone = false;
            break;
          }
        }
        if (allDone) break;
      }
    }

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    for (String id : eventIds) {
      assertEquals(EventStatus.DONE.code(), getStatus(id));
    }
    assertEquals(0, hikariDs.getHikariPoolMXBean().getActiveConnections());

    dispatcher.close();
  }

  @Test
  void rollbackReturnsConnectionToPool() throws Exception {
    OutboxWriter writer = new OutboxWriter(txContext, outboxStore);

    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      writer.write(EventEnvelope.ofJson("RollbackEvent", "{}"));
      tx.rollback();
    }

    assertEquals(0, countRows());
    assertEquals(0, hikariDs.getHikariPoolMXBean().getActiveConnections());
  }

  private OutboxDispatcher dispatcher(int workers, int hotCap, int coldCap, DefaultListenerRegistry registry) {
    return OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .listenerRegistry(registry)
        .workerCount(workers)
        .hotQueueCapacity(hotCap)
        .coldQueueCapacity(coldCap)
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

  private int countRows() throws SQLException {
    try (Connection conn = hikariDs.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM outbox_event");
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int getStatus(String eventId) throws SQLException {
    try (Connection conn = hikariDs.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT status FROM outbox_event WHERE event_id=?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : -1;
      }
    }
  }

  private void awaitStatus(String eventId, EventStatus status, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (getStatus(eventId) == status.code()) return;
      Thread.sleep(20);
    }
    assertEquals(status.code(), getStatus(eventId));
  }
}
