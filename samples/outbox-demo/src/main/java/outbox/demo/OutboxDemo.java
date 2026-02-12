package outbox.demo;

import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.DispatcherCommitHook;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.JdbcOutboxStores;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;

import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple demo showing outbox framework usage without Spring.
 *
 * Run with: mvn -pl samples/outbox-demo exec:java
 */
public final class OutboxDemo {

  public static void main(String[] args) throws Exception {
    // 1. Setup H2 in-memory database
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:demo;MODE=MySQL;DB_CLOSE_DELAY=-1");

    createSchema(dataSource);

    // 2. Create core components
    var outboxStore = JdbcOutboxStores.detect(dataSource);
    DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
    ThreadLocalTxContext txContext = new ThreadLocalTxContext();

    // Track published events
    AtomicInteger publishedCount = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(3);

    // 3. Create dispatcher with listeners and audit interceptor
    OutboxDispatcher dispatcher = OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .listenerRegistry(new DefaultListenerRegistry()
            .register("UserCreated", event -> {
              System.out.println("[Listener] UserCreated: " + event.payloadJson());
              publishedCount.incrementAndGet();
              latch.countDown();
            })
            .register("User", "UserCreated", event -> {
              System.out.println("[Listener] User/UserCreated: " + event.payloadJson());
              publishedCount.incrementAndGet();
              latch.countDown();
            })
            .register("Order", "OrderPlaced", event -> {
              System.out.println("[Listener] Order/OrderPlaced: " + event.payloadJson());
              publishedCount.incrementAndGet();
              latch.countDown();
            }))
        .inFlightTracker(new DefaultInFlightTracker(30_000))
        .workerCount(2)
        .hotQueueCapacity(100)
        .coldQueueCapacity(100)
        .interceptor(EventInterceptor.before(event ->
            System.out.println("[Audit] Event dispatched: type=" + event.eventType()
                + ", id=" + event.eventId())))
        .build();

    // 4. Create poller (fallback for missed events)
    OutboxPoller poller = OutboxPoller.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .handler(new DispatcherPollerHandler(dispatcher))
        .skipRecent(Duration.ofMillis(500))
        .batchSize(50)
        .intervalMs(1000)
        .build();
    poller.start();

    // 5. Create transaction manager and writer
    JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);
    OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherCommitHook(dispatcher));

    System.out.println("=== Outbox Demo ===\n");

    // 6. Publish events within transactions
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      // Simple event
      String eventId1 = writer.write(EventEnvelope.ofJson("UserCreated",
          "{\"userId\": 1, \"name\": \"Alice\"}"));
      System.out.println("Published UserCreated event: " + eventId1);

      // Event with metadata
      String eventId2 = writer.write(EventEnvelope.builder("OrderPlaced")
          .aggregateType("Order")
          .aggregateId("order-123")
          .tenantId("tenant-A")
          .headers(Map.of("correlationId", "corr-456", "source", "web"))
          .payloadJson("{\"orderId\": \"order-123\", \"amount\": 99.99}")
          .build());
      System.out.println("Published OrderPlaced event: " + eventId2);

      tx.commit();
      System.out.println("Transaction committed\n");
    }

    // 7. Publish another event in separate transaction
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      String eventId = writer.write(EventEnvelope.builder("UserCreated")
          .aggregateType("User")
          .aggregateId("user-2")
          .payloadJson("{\"userId\": 2, \"name\": \"Bob\"}")
          .build());
      System.out.println("Published UserCreated event: " + eventId);

      tx.commit();
      System.out.println("Transaction committed\n");
    }

    // 8. Wait for events to be processed
    System.out.println("Waiting for events to be processed...\n");
    boolean completed = latch.await(5, TimeUnit.SECONDS);

    if (completed) {
      System.out.println("\nAll " + publishedCount.get() + " events processed successfully!");
    } else {
      System.out.println("\nTimeout waiting for events. Processed: " + publishedCount.get());
    }

    // 9. Show final database state
    System.out.println("\n=== Database State ===");
    showOutboxState(dataSource);

    // 10. Cleanup
    poller.close();
    dispatcher.close();

    System.out.println("\nDemo complete.");
  }

  private static void createSchema(JdbcDataSource dataSource) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         var stmt1 = conn.createStatement();
         var stmt2 = conn.createStatement()) {
      stmt1.execute(
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
      stmt2.execute(
          "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)"
      );
    }
  }

  private static void showOutboxState(JdbcDataSource dataSource) throws SQLException {
    try (Connection conn = dataSource.getConnection();
         var stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT event_id, event_type, status, attempts, " +
                 "CASE status WHEN 0 THEN 'NEW' WHEN 1 THEN 'DONE' WHEN 2 THEN 'RETRY' WHEN 3 THEN 'DEAD' END as status_name " +
                 "FROM outbox_event ORDER BY created_at")) {
      System.out.printf("%-36s | %-15s | %-6s | %s%n", "EVENT_ID", "TYPE", "STATUS", "ATTEMPTS");
      System.out.println("-".repeat(80));
      while (rs.next()) {
        System.out.printf("%-36s | %-15s | %-6s | %d%n",
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("status_name"),
            rs.getInt("attempts"));
      }
    }
  }
}
