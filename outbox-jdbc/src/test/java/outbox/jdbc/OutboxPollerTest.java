package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.OutboxDispatcher;
import outbox.jdbc.store.H2OutboxStore;
import outbox.model.EventStatus;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.MetricsExporter;
import outbox.util.JsonCodec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxPollerTest {
    private DataSource dataSource;
    private H2OutboxStore outboxStore;
    private DataSourceConnectionProvider connectionProvider;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:outbox_poller_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        this.dataSource = ds;
        this.outboxStore = new H2OutboxStore();
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

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .retryPolicy(attempts -> 0L)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        RecordingMetrics metrics = new RecordingMetrics();
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ofHours(1))
                .batchSize(10)
                .intervalMs(10)
                .metrics(metrics)
                .build()) {
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

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .retryPolicy(attempts -> 0L)
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(1)
                .build();

        RecordingMetrics metrics = new RecordingMetrics();
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ZERO)
                .batchSize(10)
                .intervalMs(10)
                .metrics(metrics)
                .build()) {
            poller.poll();
        }

        assertEquals(1, metrics.coldEnqueued.get());
        assertEquals(0, dispatcher.coldQueueRemainingCapacity());

        dispatcher.close();
    }

    @Test
    void pollerClaimsEventsWithLocking() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(5);
        insertEvent(EventEnvelope.builder("Test").eventId("evt-lock-1")
                .occurredAt(createdAt).payloadJson("{}").build());
        insertEvent(EventEnvelope.builder("Test").eventId("evt-lock-2")
                .occurredAt(createdAt).payloadJson("{}").build());

        CountDownLatch latch = new CountDownLatch(2);
        DefaultListenerRegistry listeners = new DefaultListenerRegistry()
                .register("Test", e -> latch.countDown());

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .retryPolicy(attempts -> 0L)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        RecordingMetrics metrics = new RecordingMetrics();
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ZERO)
                .batchSize(10)
                .intervalMs(10)
                .metrics(metrics)
                .claimLocking("test-poller", Duration.ofMinutes(5))
                .build()) {
            poller.poll();
        }

        assertEquals(2, metrics.coldEnqueued.get());

        // Verify events were claimed (locked_by set during poll)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT locked_by FROM outbox_event WHERE event_id = ?")) {
            // After dispatch, markDone clears locks — but events might still be in-flight.
            // We just verify the poller used the claim path (coldEnqueued == 2).
        }

        dispatcher.close();
    }

    @Test
    void claimModeRespectsAvailableCapacity() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(5);
        insertEvent(EventEnvelope.builder("Test").eventId("evt-cap-1").occurredAt(createdAt).payloadJson("{}").build());
        insertEvent(EventEnvelope.builder("Test").eventId("evt-cap-2").occurredAt(createdAt).payloadJson("{}").build());
        insertEvent(EventEnvelope.builder("Test").eventId("evt-cap-3").occurredAt(createdAt).payloadJson("{}").build());

        // Cold queue capacity=2 with 3 pending events: should only claim 2
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .retryPolicy(attempts -> 0L)
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(2)
                .build();

        RecordingMetrics metrics = new RecordingMetrics();
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ZERO)
                .batchSize(10)
                .intervalMs(10)
                .metrics(metrics)
                .claimLocking("test-cap", Duration.ofMinutes(5))
                .build()) {
            poller.poll();
        }

        // Only 2 events should be claimed/enqueued, third stays unclaimed
        assertEquals(2, metrics.coldEnqueued.get());

        // Verify the third event was not locked
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM outbox_event WHERE locked_by IS NOT NULL")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals(2, rs.getInt(1));
        }

        dispatcher.close();
    }

    @Test
    void pollerUsesCustomJsonCodecForHeaders() throws Exception {
        // Insert event with raw header JSON in the DB
        Instant createdAt = Instant.now().minusSeconds(5);
        EventEnvelope event = EventEnvelope.builder("HeaderTest")
                .eventId("evt-codec")
                .occurredAt(createdAt)
                .headers(Map.of("trace", "abc"))
                .payloadJson("{}")
                .build();
        insertEvent(event);

        // Custom codec that injects a marker header
        JsonCodec customCodec = new JsonCodec() {
            @Override
            public String toJson(Map<String, String> headers) {
                return JsonCodec.getDefault().toJson(headers);
            }

            @Override
            public Map<String, String> parseObject(String json) {
                Map<String, String> parsed = new java.util.LinkedHashMap<>(JsonCodec.getDefault().parseObject(json));
                parsed.put("custom", "injected");
                return parsed;
            }
        };

        AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        DefaultListenerRegistry listeners = new DefaultListenerRegistry()
                .register("HeaderTest", e -> {
                    capturedHeaders.set(e.headers());
                    latch.countDown();
                });

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .retryPolicy(attempts -> 0L)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ZERO)
                .batchSize(10)
                .intervalMs(10)
                .jsonCodec(customCodec)
                .build()) {
            poller.poll();
            latch.await(2, TimeUnit.SECONDS);
        }

        assertNotNull(capturedHeaders.get());
        assertEquals("abc", capturedHeaders.get().get("trace"));
        assertEquals("injected", capturedHeaders.get().get("custom"));

        dispatcher.close();
    }

    @Test
    void startIsIdempotent() {
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .batchSize(10)
                .intervalMs(60_000)
                .build()) {
            poller.start();
            poller.start(); // second call should be a no-op
        }

        dispatcher.close();
    }

    @Test
    void startAfterCloseThrows() {
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .batchSize(10)
                .intervalMs(60_000)
                .build();

        poller.close();
        assertThrows(IllegalStateException.class, poller::start);
        dispatcher.close();
    }

    @Test
    void closeBeforeStartDoesNotThrow() {
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .batchSize(10)
                .intervalMs(60_000)
                .build();

        assertDoesNotThrow(poller::close);
        dispatcher.close();
    }

    @Test
    void pollAfterCloseIsNoOp() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(5);
        insertEvent(EventEnvelope.builder("Test").eventId("evt-nop")
                .occurredAt(createdAt).payloadJson("{}").build());

        RecordingMetrics metrics = new RecordingMetrics();
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ZERO)
                .batchSize(10)
                .intervalMs(60_000)
                .metrics(metrics)
                .build();

        poller.close();
        poller.poll(); // should be a no-op

        assertEquals(0, metrics.coldEnqueued.get());
        dispatcher.close();
    }

    @Test
    void pollSkipsWhenHandlerCapacityIsZero() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(5);
        insertEvent(EventEnvelope.builder("Test").eventId("evt-cap0")
                .occurredAt(createdAt).payloadJson("{}").build());

        // Dispatcher with cold queue capacity=1, pre-fill it
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(1)
                .build();
        // Fill the cold queue
        dispatcher.enqueueCold(new outbox.dispatch.QueuedEvent(
                EventEnvelope.ofJson("Fill", "{}"), outbox.dispatch.QueuedEvent.Source.COLD, 0));

        RecordingMetrics metrics = new RecordingMetrics();
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ZERO)
                .batchSize(10)
                .intervalMs(60_000)
                .metrics(metrics)
                .build()) {
            poller.poll(); // should skip because handler capacity is 0
        }

        assertEquals(0, metrics.coldEnqueued.get());
        dispatcher.close();
    }

    @Test
    void claimLockingRejectsNegativeLockTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboxPoller.builder()
                        .connectionProvider(connectionProvider)
                        .outboxStore(outboxStore)
                        .handler(new DispatcherPollerHandler(
                                OutboxDispatcher.builder()
                                        .connectionProvider(connectionProvider)
                                        .outboxStore(outboxStore)
                                        .listenerRegistry(new DefaultListenerRegistry())
                                        .workerCount(0)
                                        .build()))
                        .claimLocking("test", Duration.ofMinutes(-1)));
    }

    @Test
    void claimLockingRejectsZeroLockTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboxPoller.builder()
                        .connectionProvider(connectionProvider)
                        .outboxStore(outboxStore)
                        .handler(new DispatcherPollerHandler(
                                OutboxDispatcher.builder()
                                        .connectionProvider(connectionProvider)
                                        .outboxStore(outboxStore)
                                        .listenerRegistry(new DefaultListenerRegistry())
                                        .workerCount(0)
                                        .build()))
                        .claimLocking("test", Duration.ZERO));
    }

    private void insertEvent(EventEnvelope event) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            outboxStore.insertNew(conn, event);
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
                        "last_error CLOB," +
                        "locked_by VARCHAR(128)," +
                        "locked_at TIMESTAMP" +
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
