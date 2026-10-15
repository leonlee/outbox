package io.outbox.jdbc;

import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.dispatch.DefaultInFlightTracker;
import io.outbox.dispatch.InFlightTracker;
import io.outbox.dispatch.OutboxDispatcher;
import io.outbox.dispatch.QueuedEvent;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.H2OutboxStore;
import io.outbox.model.EventStatus;
import io.outbox.registry.DefaultListenerRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDispatcherTest {
    private DataSource dataSource;
    private H2OutboxStore outboxStore;
    private DataSourceConnectionProvider connectionProvider;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:outbox_dispatcher_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
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
    void hotQueueIsPrioritizedOverColdQueue() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch processed = new CountDownLatch(2);

        DefaultListenerRegistry listeners = new DefaultListenerRegistry()
                .register("Test", event -> {
                    order.add(event.eventId());
                    processed.countDown();
                    return DispatchResult.done();
                });

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        EventEnvelope cold = EventEnvelope.builder("Test").eventId("cold").payloadJson("{}").build();
        EventEnvelope hot = EventEnvelope.builder("Test").eventId("hot").payloadJson("{}").build();

        dispatcher.enqueueCold(new QueuedEvent(cold, QueuedEvent.Source.COLD, 0));
        dispatcher.enqueueHot(new QueuedEvent(hot, QueuedEvent.Source.HOT, 0));

        AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread worker = startWorker(dispatcher, workerError);

        assertTrue(processed.await(2, TimeUnit.SECONDS));

        dispatcher.close();
        worker.interrupt();
        worker.join(1000);

        if (workerError.get() != null) {
            throw new AssertionError("Worker failed", workerError.get());
        }

        assertEquals(List.of("hot", "cold"), order);
    }

    @Test
    void failureMarksRetry() throws Exception {
        DefaultListenerRegistry listeners = new DefaultListenerRegistry()
                .register("Test", event -> {
                    throw new RuntimeException("boom");
                });

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .retryPolicy(attempts -> 0L)
                .maxAttempts(3)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        EventEnvelope event = EventEnvelope.builder("Test").eventId("evt-retry").payloadJson("{}").build();
        insertEvent(event);

        dispatcher.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

        awaitStatus(event.eventId(), EventStatus.RETRY, 2_000);

        dispatcher.close();
    }

    @Test
    void failureMarksDeadWhenMaxAttemptsReached() throws Exception {
        DefaultListenerRegistry listeners = new DefaultListenerRegistry()
                .register("Test", event -> {
                    throw new RuntimeException("boom");
                });

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .retryPolicy(attempts -> 0L)
                .maxAttempts(1)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        EventEnvelope event = EventEnvelope.builder("Test").eventId("evt-dead").payloadJson("{}").build();
        insertEvent(event);

        dispatcher.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

        awaitStatus(event.eventId(), EventStatus.DEAD, 2_000);

        dispatcher.close();
    }

    @Test
    void inFlightPreventsConcurrentDuplicateDispatchAndAllowsLater() throws Exception {
        RecordingInFlightTracker inFlightTracker = new RecordingInFlightTracker();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        DefaultListenerRegistry listeners = new DefaultListenerRegistry()
                .register("Test", event -> {
                    int count = calls.incrementAndGet();
                    if (count == 1) {
                        firstStarted.countDown();
                        releaseFirst.await();
                    }
                    return DispatchResult.done();
                });

        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listeners)
                .inFlightTracker(inFlightTracker)
                .retryPolicy(attempts -> 0L)
                .workerCount(0)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .build();

        Method dispatchEvent = OutboxDispatcher.class.getDeclaredMethod("dispatchEvent", QueuedEvent.class);
        dispatchEvent.setAccessible(true);

        QueuedEvent first = new QueuedEvent(
                EventEnvelope.builder("Test").eventId("dup").payloadJson("{}").build(),
                QueuedEvent.Source.HOT,
                0
        );
        QueuedEvent second = new QueuedEvent(
                EventEnvelope.builder("Test").eventId("dup").payloadJson("{}").build(),
                QueuedEvent.Source.HOT,
                0
        );

        AtomicReference<Throwable> dispatchError = new AtomicReference<>();
        Thread t1 = new Thread(() -> invokeDispatch(dispatchEvent, dispatcher, first, dispatchError));
        Thread t2 = new Thread(() -> invokeDispatch(dispatchEvent, dispatcher, second, dispatchError));

        t1.start();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        t2.start();
        t2.join(1000);

        assertEquals(1, calls.get());
        assertTrue(inFlightTracker.failedCount() > 0);

        releaseFirst.countDown();
        t1.join(1000);

        QueuedEvent third = new QueuedEvent(
                EventEnvelope.builder("Test").eventId("dup").payloadJson("{}").build(),
                QueuedEvent.Source.HOT,
                0
        );
        invokeDispatch(dispatchEvent, dispatcher, third, dispatchError);

        if (dispatchError.get() != null) {
            throw new AssertionError("Dispatch failed", dispatchError.get());
        }

        assertEquals(2, calls.get());

        dispatcher.close();
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

    private Thread startWorker(OutboxDispatcher dispatcher, AtomicReference<Throwable> errorRef) throws Exception {
        Method workerLoop = OutboxDispatcher.class.getDeclaredMethod("workerLoop");
        workerLoop.setAccessible(true);
        Thread worker = new Thread(() -> {
            try {
                workerLoop.invoke(dispatcher);
            } catch (Throwable t) {
                errorRef.compareAndSet(null, t.getCause() == null ? t : t.getCause());
            }
        });
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private void invokeDispatch(Method dispatchEvent, OutboxDispatcher dispatcher, QueuedEvent event,
                                AtomicReference<Throwable> errorRef) {
        try {
            dispatchEvent.invoke(dispatcher, event);
        } catch (Throwable t) {
            errorRef.compareAndSet(null, t.getCause() == null ? t : t.getCause());
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

    private static final class RecordingInFlightTracker implements InFlightTracker {
        private final DefaultInFlightTracker delegate = new DefaultInFlightTracker();
        private final AtomicInteger failed = new AtomicInteger();

        @Override
        public boolean tryAcquire(String eventId) {
            boolean acquired = delegate.tryAcquire(eventId);
            if (!acquired) {
                failed.incrementAndGet();
            }
            return acquired;
        }

        @Override
        public void release(String eventId) {
            delegate.release(eventId);
        }

        private int failedCount() {
            return failed.get();
        }
    }
    /**
     * A writer and a dispatcher wired with separate store instances of one owner: the shutdown release
     * still hands back the known stamps — a stranded hot copy's, and one whose status write the forced
     * shutdown cut short — because one event's stamp is matched across the owner's runs in this JVM.
     */
    @Test
    void separateWriterAndDispatcherStoresStillHandBackKnownStamps() throws Exception {
        H2OutboxStore writer = new H2OutboxStore("outbox_event", "pod-a");
        LockingH2Store dispatcherStore = new LockingH2Store("pod-a");
        CountDownLatch running = new CountDownLatch(1);
        DefaultListenerRegistry listeners = new DefaultListenerRegistry().register("Test", event -> {
            running.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS); // until shutdownNow interrupts it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });
        EventEnvelope cutShort = EventEnvelope.builder("Test").payloadJson("{}").build();
        EventEnvelope stranded = EventEnvelope.builder("Test").payloadJson("{}").build();
        try (Connection conn = dataSource.getConnection()) {
            writer.insertNew(conn, cutShort);
            writer.insertNew(conn, stranded);
        }
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider).outboxStore(dispatcherStore).listenerRegistry(listeners)
                .claimOwner("pod-a").workerCount(1).drainTimeoutMs(100)
                .build();
        assertTrue(dispatcher.enqueueHot(new QueuedEvent(cutShort, QueuedEvent.Source.HOT, 0)));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.enqueueHot(new QueuedEvent(stranded, QueuedEvent.Source.HOT, 0)));

        dispatcher.close();

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(null, lockedBy(conn, cutShort.eventId()), "the cut-short hot write's stamp");
            assertEquals(null, lockedBy(conn, stranded.eventId()), "the stranded hot copy's stamp");
        }
    }

    /**
     * With the usual wiring — one store for writer and dispatcher — close() ends the store's run before
     * the release, yet a known hot copy's stamp is still handed back by its own release, not left to a
     * capped sweep that may not reach it (here the sweep finds nothing at all).
     */
    @Test
    void aSharedStoresKnownStampIsReleasedEvenWhenTheSweepMissesIt() throws Exception {
        LockingH2Store shared = new LockingH2Store("pod-a") {
            @Override
            public List<String> findStamped(Connection conn, String stamp, int limit) {
                return List.of(); // as if beyond the sweep's cap
            }
        };
        EventEnvelope stranded = EventEnvelope.builder("Test").payloadJson("{}").build();
        try (Connection conn = dataSource.getConnection()) {
            shared.insertNew(conn, stranded);
        }
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider).outboxStore(shared)
                .listenerRegistry(new DefaultListenerRegistry())
                .claimOwner("pod-a").workerCount(0)
                .build();
        assertTrue(dispatcher.enqueueHot(new QueuedEvent(stranded, QueuedEvent.Source.HOT, 0)));

        dispatcher.close();

        try (Connection conn = dataSource.getConnection()) {
            assertEquals(null, lockedBy(conn, stranded.eventId()), "released by its own known release");
        }
    }

    /**
     * An H2 store reporting exclusive claims, so the dispatcher's shutdown release runs; its status writes
     * fail while the thread is interrupted, as an interrupt-sensitive pool's would.
     */
    private static class LockingH2Store extends AbstractJdbcOutboxStore {
        LockingH2Store(String owner) {
            super("outbox_event", owner);
        }

        @Override
        public String name() {
            return "h2-locking";
        }

        @Override
        public List<String> jdbcUrlPrefixes() {
            return List.of();
        }

        @Override
        public boolean supportsClaimLocking() {
            return true;
        }

        @Override
        public int markDone(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted during connection acquisition");
            }
            return super.markDone(conn, eventId, claimOwner, claimedAt);
        }
    }

    private static String lockedBy(Connection conn, String eventId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT locked_by FROM outbox_event WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

}
