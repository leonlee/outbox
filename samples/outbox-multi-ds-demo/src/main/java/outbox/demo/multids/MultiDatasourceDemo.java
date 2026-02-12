package outbox.demo.multids;

import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.dispatch.DispatcherCommitHook;
import outbox.dispatch.DispatcherPollerHandler;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the multi-datasource outbox pattern: two independent H2 databases
 * ("Orders" and "Inventory"), each with its own full outbox stack, sharing a
 * stateless {@code EventListener}.
 *
 * <p>Run with: {@code mvn install -DskipTests && mvn -pl samples/outbox-multi-ds-demo exec:java}
 */
public final class MultiDatasourceDemo {

  public static void main(String[] args) throws Exception {
    // ── Datasources ──────────────────────────────────────────────
    JdbcDataSource ordersDs = new JdbcDataSource();
    ordersDs.setURL("jdbc:h2:mem:orders;MODE=MySQL;DB_CLOSE_DELAY=-1");
    createSchema(ordersDs);

    JdbcDataSource inventoryDs = new JdbcDataSource();
    inventoryDs.setURL("jdbc:h2:mem:inventory;MODE=MySQL;DB_CLOSE_DELAY=-1");
    createSchema(inventoryDs);

    // ── Shared listener (stateless — safe to reuse across stacks) ──
    CountDownLatch latch = new CountDownLatch(4);
    outbox.EventListener sharedListener = event -> {
      System.out.printf("[Listener] %s/%s  id=%s  payload=%s%n",
          event.aggregateType(), event.eventType(), event.eventId(), event.payloadJson());
      latch.countDown();
    };

    // ── Orders stack ─────────────────────────────────────────────
    var ordersOutboxStore = JdbcOutboxStores.detect(ordersDs);
    var ordersConn = new DataSourceConnectionProvider(ordersDs);
    var ordersTx = new ThreadLocalTxContext();

    OutboxDispatcher ordersDispatcher = OutboxDispatcher.builder()
        .connectionProvider(ordersConn)
        .outboxStore(ordersOutboxStore)
        .listenerRegistry(new DefaultListenerRegistry()
            .register("Order", "OrderPlaced", sharedListener)
            .register("Order", "OrderShipped", sharedListener))
        .build();

    OutboxPoller ordersPoller = OutboxPoller.builder()
        .connectionProvider(ordersConn)
        .outboxStore(ordersOutboxStore)
        .handler(new DispatcherPollerHandler(ordersDispatcher))
        .skipRecent(Duration.ofMillis(500))
        .batchSize(50)
        .intervalMs(1000)
        .build();
    ordersPoller.start();

    JdbcTransactionManager ordersTxMgr = new JdbcTransactionManager(ordersConn, ordersTx);
    OutboxWriter ordersWriter = new OutboxWriter(
        ordersTx, ordersOutboxStore, new DispatcherCommitHook(ordersDispatcher));

    // ── Inventory stack ──────────────────────────────────────────
    var invOutboxStore = JdbcOutboxStores.detect(inventoryDs);
    var invConn = new DataSourceConnectionProvider(inventoryDs);
    var invTx = new ThreadLocalTxContext();

    OutboxDispatcher invDispatcher = OutboxDispatcher.builder()
        .connectionProvider(invConn)
        .outboxStore(invOutboxStore)
        .listenerRegistry(new DefaultListenerRegistry()
            .register("Inventory", "StockReserved", sharedListener)
            .register("Inventory", "StockDepleted", sharedListener))
        .build();

    OutboxPoller invPoller = OutboxPoller.builder()
        .connectionProvider(invConn)
        .outboxStore(invOutboxStore)
        .handler(new DispatcherPollerHandler(invDispatcher))
        .skipRecent(Duration.ofMillis(500))
        .batchSize(50)
        .intervalMs(1000)
        .build();
    invPoller.start();

    JdbcTransactionManager invTxMgr = new JdbcTransactionManager(invConn, invTx);
    OutboxWriter invWriter = new OutboxWriter(
        invTx, invOutboxStore, new DispatcherCommitHook(invDispatcher));

    // ── Publish events ───────────────────────────────────────────
    System.out.println("=== Multi-Datasource Demo ===\n");

    try (var tx = ordersTxMgr.begin()) {
      ordersWriter.write(EventEnvelope.builder("OrderPlaced")
          .aggregateType("Order").aggregateId("order-42")
          .payloadJson("{\"item\":\"widget\",\"qty\":1}")
          .build());
      ordersWriter.write(EventEnvelope.builder("OrderShipped")
          .aggregateType("Order").aggregateId("order-42")
          .payloadJson("{\"carrier\":\"FedEx\"}")
          .build());
      tx.commit();
      System.out.println("Orders transaction committed (2 events)");
    }

    try (var tx = invTxMgr.begin()) {
      invWriter.write(EventEnvelope.builder("StockReserved")
          .aggregateType("Inventory").aggregateId("sku-99")
          .payloadJson("{\"qty\":5}")
          .build());
      invWriter.write(EventEnvelope.builder("StockDepleted")
          .aggregateType("Inventory").aggregateId("sku-99")
          .payloadJson("{\"qty\":0}")
          .build());
      tx.commit();
      System.out.println("Inventory transaction committed (2 events)");
    }

    // ── Wait & report ────────────────────────────────────────────
    System.out.println("\nWaiting for events to be processed...\n");
    boolean completed = latch.await(5, TimeUnit.SECONDS);

    if (completed) {
      System.out.println("\nAll 4 events processed successfully!");
    } else {
      System.out.println("\nTimeout — some events not yet processed.");
    }

    // Brief pause to let dispatcher status updates flush to DB
    Thread.sleep(200);

    System.out.println("\n=== Orders Database ===");
    showOutboxState(ordersDs);

    System.out.println("\n=== Inventory Database ===");
    showOutboxState(inventoryDs);

    // ── Cleanup ──────────────────────────────────────────────────
    ordersPoller.close();
    ordersDispatcher.close();
    invPoller.close();
    invDispatcher.close();

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
             "SELECT event_id, event_type, aggregate_type, status, attempts, " +
                 "CASE status WHEN 0 THEN 'NEW' WHEN 1 THEN 'DONE' WHEN 2 THEN 'RETRY' WHEN 3 THEN 'DEAD' END as status_name " +
                 "FROM outbox_event ORDER BY created_at")) {
      System.out.printf("%-36s | %-15s | %-10s | %-6s | %s%n",
          "EVENT_ID", "TYPE", "AGGREGATE", "STATUS", "ATTEMPTS");
      System.out.println("-".repeat(90));
      while (rs.next()) {
        System.out.printf("%-36s | %-15s | %-10s | %-6s | %d%n",
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("aggregate_type"),
            rs.getString("status_name"),
            rs.getInt("attempts"));
      }
    }
  }
}
