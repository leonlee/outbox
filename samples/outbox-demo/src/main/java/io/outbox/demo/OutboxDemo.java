package io.outbox.demo;

import org.h2.jdbcx.JdbcDataSource;
import io.outbox.EventEnvelope;
import io.outbox.Outbox;
import io.outbox.dispatch.EventInterceptor;
import io.outbox.jdbc.DataSourceConnectionProvider;
import io.outbox.jdbc.store.JdbcOutboxStores;
import io.outbox.jdbc.tx.JdbcTransactionManager;
import io.outbox.jdbc.tx.ThreadLocalTxContext;
import io.outbox.registry.DefaultListenerRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple demo showing outbox framework usage without Spring.
 * <p>
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
        var connectionProvider = new DataSourceConnectionProvider(dataSource);
        var txContext = new ThreadLocalTxContext();

        // Track published events
        AtomicInteger publishedCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);

        // 3. Build outbox using the composite builder (recommended approach)
        try (Outbox outbox = Outbox.singleNode()
                .connectionProvider(connectionProvider)
                .txContext(txContext)
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
                .interceptor(EventInterceptor.before(event ->
                        System.out.println("[Audit] Event dispatched: type=" + event.eventType()
                                + ", id=" + event.eventId())))
                .workerCount(2)
                .hotQueueCapacity(100)
                .coldQueueCapacity(100)
                .intervalMs(1000)
                .build()) {

            // 4. Create transaction manager
            JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);

            System.out.println("=== Outbox Demo ===\n");

            // 5. Publish events within transactions
            try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
                // Simple event
                String eventId1 = outbox.writer().write(EventEnvelope.ofJson("UserCreated",
                        "{\"userId\": 1, \"name\": \"Alice\"}"));
                System.out.println("Published UserCreated event: " + eventId1);

                // Event with metadata
                String eventId2 = outbox.writer().write(EventEnvelope.builder("OrderPlaced")
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

            // 6. Publish another event in separate transaction
            try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
                String eventId = outbox.writer().write(EventEnvelope.builder("UserCreated")
                        .aggregateType("User")
                        .aggregateId("user-2")
                        .payloadJson("{\"userId\": 2, \"name\": \"Bob\"}")
                        .build());
                System.out.println("Published UserCreated event: " + eventId);

                tx.commit();
                System.out.println("Transaction committed\n");
            }

            // 7. Wait for events to be processed
            System.out.println("Waiting for events to be processed...\n");
            boolean completed = latch.await(5, TimeUnit.SECONDS);

            if (completed) {
                System.out.println("\nAll " + publishedCount.get() + " events processed successfully!");
            } else {
                System.out.println("\nTimeout waiting for events. Processed: " + publishedCount.get());
            }

            // 8. Show final database state
            System.out.println("\n=== Database State ===");
            showOutboxState(dataSource);

        } // Outbox.close() shuts down dispatcher, poller automatically

        System.out.println("\nDemo complete.");
    }

    private static void createSchema(JdbcDataSource dataSource) throws SQLException, IOException {
        String ddl;
        try (InputStream is = OutboxDemo.class.getResourceAsStream("/schema/h2.sql")) {
            if (is == null) throw new IllegalStateException("Schema resource /schema/h2.sql not found");
            ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            for (String sql : ddl.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
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
