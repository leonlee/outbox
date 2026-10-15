package io.outbox.dispatch;

import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.RecoverableException;
import io.outbox.RetryAfterException;
import io.outbox.UnrecoverableException;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.MetricsExporter;
import io.outbox.util.IsolatingMetricsExporter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDispatcherTest {

    private static final QueuedEvent.ClaimLease LEASE =
            new QueuedEvent.ClaimLease("pod-a", java.time.Instant.parse("2026-08-05T00:00:00Z"));

    private static final String ONCE = "Once";
    private static final String TAIL = "Tail";

    // ── Builder validation ──────────────────────────────────────────

    @Test
    void builderRejectsNullConnectionProvider() {
        assertThrows(NullPointerException.class, () ->
                OutboxDispatcher.builder()
                        .outboxStore(new StubOutboxStore())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .build());
    }

    @Test
    void builderRejectsNullOutboxStore() {
        assertThrows(NullPointerException.class, () ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .build());
    }

    @Test
    void builderRejectsNullListenerRegistry() {
        assertThrows(NullPointerException.class, () ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .outboxStore(new StubOutboxStore())
                        .build());
    }

    @Test
    void builderRejectsNegativeMaxAttempts() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .outboxStore(new StubOutboxStore())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .maxAttempts(-1)
                        .build());
    }

    @Test
    void builderAcceptsZeroMaxAttempts() {
        // maxAttempts=0 means immediate DEAD on first failure
        assertDoesNotThrow(() ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .outboxStore(new StubOutboxStore())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .maxAttempts(0)
                        .build());
    }

    @Test
    void builderRejectsNegativeWorkerCount() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .outboxStore(new StubOutboxStore())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .workerCount(-1)
                        .build());
    }

    @Test
    void builderRejectsZeroQueueCapacity() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .outboxStore(new StubOutboxStore())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .hotQueueCapacity(0)
                        .build());
    }

    @Test
    void builderRejectsNullInterceptorsList() {
        assertThrows(NullPointerException.class, () ->
                OutboxDispatcher.builder().interceptors(null));
    }

    @Test
    void builderRejectsNullInterceptorsElement() {
        List<EventInterceptor> list = new java.util.ArrayList<>();
        list.add(null);
        assertThrows(NullPointerException.class, () ->
                OutboxDispatcher.builder().interceptors(list));
    }

    // ── Enqueue and lifecycle ───────────────────────────────────────

    @Test
    void enqueueHotReturnsTrueWhenSpaceAvailable() {
        try (var d = newDispatcher(0, 10, 10)) {
            QueuedEvent event = new QueuedEvent(EventEnvelope.ofJson("A", "{}"), QueuedEvent.Source.HOT, 0);
            assertTrue(d.enqueueHot(event));
        }
    }

    @Test
    void enqueueHotReturnsFalseWhenQueueFull() {
        try (var d = newDispatcher(0, 1, 10)) {
            QueuedEvent e1 = new QueuedEvent(EventEnvelope.ofJson("A", "{}"), QueuedEvent.Source.HOT, 0);
            QueuedEvent e2 = new QueuedEvent(EventEnvelope.ofJson("B", "{}"), QueuedEvent.Source.HOT, 0);
            assertTrue(d.enqueueHot(e1));
            assertFalse(d.enqueueHot(e2));
        }
    }

    @Test
    void enqueueColdReturnsFalseAfterClose() {
        try (var d = newDispatcher(0, 10, 10)) {
            d.close();

            QueuedEvent event = new QueuedEvent(EventEnvelope.ofJson("A", "{}"), QueuedEvent.Source.COLD, 0);
            assertFalse(d.enqueueCold(event));
        }
    }

    @Test
    void coldQueueRemainingCapacityReflectsEnqueues() {
        try (var d = newDispatcher(0, 10, 5)) {
            assertEquals(5, d.coldQueueRemainingCapacity());

            d.enqueueCold(new QueuedEvent(EventEnvelope.ofJson("A", "{}"), QueuedEvent.Source.COLD, 0));
            assertEquals(4, d.coldQueueRemainingCapacity());
        }
    }

    @Test
    void closeIsIdempotent() {
        try (var d = newDispatcher(0, 10, 10)) {
            assertDoesNotThrow(() -> {
                d.close();
                d.close();
            });
        }
    }

    // ── Dispatch paths ──────────────────────────────────────────────

    @Test
    void dispatchesEventToRegisteredListener() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        var registry = new DefaultListenerRegistry()
                .register("TestEvent", event -> {
                    received.set(event.payloadJson());
                    latch.countDown();
                    return DispatchResult.done();
                });

        var store = new StubOutboxStore();
        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            EventEnvelope event = EventEnvelope.ofJson("TestEvent", "{\"x\":1}");
            d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals("{\"x\":1}", received.get());

            // Give markDone time to complete
            Thread.sleep(100);
            assertTrue(store.markDoneCount.get() > 0);
        }
    }

    @Test
    void unroutableEventIsMarkedDeadImmediately() throws Exception {
        CountDownLatch deadLatch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                deadLatch.countDown();
                return 1;
            }
        };

        // Empty registry — no listeners → UnroutableEventException (UnrecoverableException) → DEAD
        var registry = new DefaultListenerRegistry();

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            EventEnvelope event = EventEnvelope.ofJson("UnknownEvent", "{}");
            d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

            assertTrue(deadLatch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeadCount.get());
            assertEquals(0, store.markRetryCount.get());
        }
    }

    @Test
    void failedEventIsMarkedRetryWhenAttemptsRemain() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markRetry(Connection conn, String eventId, java.time.Instant nextAt, String error) {
                super.markRetry(conn, eventId, nextAt, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry()
                .register("FailEvent", event -> {
                    throw new RuntimeException("boom");
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(3)

                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .build()) {

            EventEnvelope event = EventEnvelope.ofJson("FailEvent", "{}");
            d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markRetryCount.get());
            assertEquals(0, store.markDeadCount.get());
        }
    }

    @Test
    void failedEventIsMarkedDeadWhenMaxAttemptsReached() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry()
                .register("FailEvent", event -> {
                    throw new RuntimeException("boom");
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(2)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .build()) {

            // attempts=1, maxAttempts=2, so nextAttempt (1+1=2) >= maxAttempts -> DEAD
            EventEnvelope event = EventEnvelope.ofJson("FailEvent", "{}");
            d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 1));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeadCount.get());
        }
    }

    @Test
    void interceptorsAreCalledInOrder() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger beforeOrder = new AtomicInteger();
        AtomicInteger afterOrder = new AtomicInteger();

        AtomicInteger firstBeforeAt = new AtomicInteger();
        AtomicInteger secondBeforeAt = new AtomicInteger();
        AtomicInteger firstAfterAt = new AtomicInteger();
        AtomicInteger secondAfterAt = new AtomicInteger();

        var registry = new DefaultListenerRegistry()
                .register("Test", event -> DispatchResult.done());

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .interceptor(new EventInterceptor() {
                    @Override
                    public void beforeDispatch(EventEnvelope event) {
                        firstBeforeAt.set(beforeOrder.incrementAndGet());
                    }

                    @Override
                    public void afterDispatch(EventEnvelope event, Exception error) {
                        firstAfterAt.set(afterOrder.incrementAndGet());
                        latch.countDown();
                    }
                })
                .interceptor(new EventInterceptor() {
                    @Override
                    public void beforeDispatch(EventEnvelope event) {
                        secondBeforeAt.set(beforeOrder.incrementAndGet());
                    }

                    @Override
                    public void afterDispatch(EventEnvelope event, Exception error) {
                        secondAfterAt.set(afterOrder.incrementAndGet());
                    }
                })
                .build()) {

            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("Test", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            // beforeDispatch in registration order: first=1, second=2
            assertEquals(1, firstBeforeAt.get());
            assertEquals(2, secondBeforeAt.get());
            // afterDispatch in reverse order: second=1, first=2
            assertEquals(1, secondAfterAt.get());
            assertEquals(2, firstAfterAt.get());
        }
    }

    @Test
    void unrecoverableExceptionIsMarkedDeadImmediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry()
                .register("UnrecoverableEvent", event -> {
                    throw new UnrecoverableException("bad payload");
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(5)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .build()) {

            // Even with attempts=0 and maxAttempts=5, should go straight to DEAD
            EventEnvelope event = EventEnvelope.ofJson("UnrecoverableEvent", "{}");
            d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeadCount.get());
            assertEquals(0, store.markRetryCount.get());
        }
    }

    // ── Multi-worker and shutdown ──────────────────────────────────

    @Test
    void multiWorkerDispatchesAllEventsExactlyOnce() throws Exception {
        int eventCount = 50;
        CountDownLatch latch = new CountDownLatch(eventCount);
        Set<String> processed = ConcurrentHashMap.newKeySet();

        var registry = new DefaultListenerRegistry()
                .register("MW", event -> {
                    processed.add(event.eventId());
                    latch.countDown();
                    return DispatchResult.done();
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(4)
                .hotQueueCapacity(100)
                .coldQueueCapacity(100)
                .drainTimeoutMs(5000)
                .build()) {

            for (int i = 0; i < eventCount; i++) {
                EventEnvelope event = EventEnvelope.builder("MW")
                        .eventId("mw-" + i)
                        .payloadJson("{}")
                        .build();
                d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS), "All events should be processed");
            assertEquals(eventCount, processed.size(), "Each event processed exactly once");
        }
    }

    @Test
    void multiWorkerProcessesBothQueues() throws Exception {
        int hotCount = 20;
        int coldCount = 10;
        CountDownLatch latch = new CountDownLatch(hotCount + coldCount);
        Set<String> processed = ConcurrentHashMap.newKeySet();

        var registry = new DefaultListenerRegistry()
                .register("MWQ", event -> {
                    processed.add(event.eventId());
                    latch.countDown();
                    return DispatchResult.done();
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(2)
                .hotQueueCapacity(100)
                .coldQueueCapacity(100)
                .drainTimeoutMs(5000)
                .build()) {

            for (int i = 0; i < hotCount; i++) {
                d.enqueueHot(new QueuedEvent(
                        EventEnvelope.builder("MWQ").eventId("hot-" + i).payloadJson("{}").build(),
                        QueuedEvent.Source.HOT, 0));
            }
            for (int i = 0; i < coldCount; i++) {
                d.enqueueCold(new QueuedEvent(
                        EventEnvelope.builder("MWQ").eventId("cold-" + i).payloadJson("{}").build(),
                        QueuedEvent.Source.COLD, 0));
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS), "All hot+cold events should be processed");
            assertEquals(hotCount + coldCount, processed.size());
        }
    }

    @Test
    void shutdownDrainsQueuedEvents() throws Exception {
        int eventCount = 10;
        CountDownLatch latch = new CountDownLatch(eventCount);
        Set<String> processed = ConcurrentHashMap.newKeySet();

        var registry = new DefaultListenerRegistry()
                .register("Drain", event -> {
                    processed.add(event.eventId());
                    latch.countDown();
                    return DispatchResult.done();
                });

        var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(2)
                .hotQueueCapacity(100)
                .coldQueueCapacity(100)
                .drainTimeoutMs(5000)
                .build();

        for (int i = 0; i < eventCount; i++) {
            d.enqueueHot(new QueuedEvent(
                    EventEnvelope.builder("Drain").eventId("drain-" + i).payloadJson("{}").build(),
                    QueuedEvent.Source.HOT, 0));
        }

        // Close should drain remaining events within timeout
        d.close();

        assertTrue(latch.await(1, TimeUnit.SECONDS),
                "Events enqueued before close should be drained");
        assertEquals(eventCount, processed.size());
    }

    /**
     * A worker whose blocking poll is parked on the cold queue must still drain the hot queue
     * on shutdown. An idle single worker parks for QUEUE_POLL_TIMEOUT_MS per cycle and the
     * primary queue is the cold one on every third cycle, so the parks that ignore hot events
     * recur every three cycles. Sweeping the delay across more than one full cycle lands inside
     * such a park whatever the machine's timing; each attempt asserts the drain contract, and
     * close() is synchronous, so the latch is final by the time it returns.
     */
    @Test
    void shutdownDrainsHotQueueWhileWorkerIsParkedOnColdQueue() throws Exception {
        for (long delayMs = 100; delayMs <= 250; delayMs += 15) {
            int eventCount = 10;
            CountDownLatch latch = new CountDownLatch(eventCount);

            var registry = new DefaultListenerRegistry()
                    .register("Drain", event -> {
                        latch.countDown();
                        return DispatchResult.done();
                    });

            var d = OutboxDispatcher.builder()
                    .connectionProvider(stubCp())
                    .outboxStore(new StubOutboxStore())
                    .listenerRegistry(registry)
                    .workerCount(1)
                    .hotQueueCapacity(100)
                    .coldQueueCapacity(100)
                    .drainTimeoutMs(5000)
                    .build();

            Thread.sleep(delayMs);
            for (int i = 0; i < eventCount; i++) {
                d.enqueueHot(new QueuedEvent(
                        EventEnvelope.builder("Drain").eventId("drain-" + delayMs + "-" + i)
                                .payloadJson("{}").build(),
                        QueuedEvent.Source.HOT, 0));
            }

            d.close();

            assertEquals(0L, latch.getCount(),
                    "close() after a " + delayMs + "ms idle period must drain the hot queue");
        }
    }

    @Test
    void enqueueRejectedAfterClose() {
        var d = newDispatcher(1, 10, 10);
        d.close();

        EventEnvelope event = EventEnvelope.ofJson("Test", "{}");
        assertFalse(d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0)));
        assertFalse(d.enqueueCold(new QueuedEvent(event, QueuedEvent.Source.COLD, 0)));
    }

    @Test
    void builderRejectsNullInterceptor() {
        assertThrows(NullPointerException.class, () ->
                OutboxDispatcher.builder().interceptor(null));
    }

    // ── DispatchResult dispatch paths ────────────────────────────────

    @Test
    void handlerReturningDoneMarksEventDone() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDone(Connection conn, String eventId) {
                super.markDone(conn, eventId);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("DoneResult", event -> DispatchResult.done());

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("DoneResult", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDoneCount.get());
            assertEquals(0, store.markDeferredCount.get());
        }
    }

    @Test
    void handlerReturningRetryAfterMarksDeferredNotDone() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDeferred(Connection conn, String eventId, java.time.Instant nextAt) {
                super.markDeferred(conn, eventId, nextAt);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("DeferResult", event -> DispatchResult.retryAfter(Duration.ofSeconds(30)));

        java.time.Instant before = java.time.Instant.now();
        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("DeferResult", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeferredCount.get());
            assertEquals(0, store.markDoneCount.get());
            assertEquals(0, store.markRetryCount.get());

            // Verify the delay value: nextAt should be ~now + 30s
            java.time.Instant nextAt = store.lastDeferredNextAt.get();
            assertTrue(nextAt.isAfter(before.plusSeconds(29)),
                    "nextAt should be at least 29s after test start, was: " + nextAt);
            assertTrue(nextAt.isBefore(before.plusSeconds(35)),
                    "nextAt should be within 35s of test start, was: " + nextAt);
        }
    }

    @Test
    void handlerReturningNullCausesRetry() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markRetry(Connection conn, String eventId, java.time.Instant nextAt, String error) {
                super.markRetry(conn, eventId, nextAt, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("NullResult", event -> null);

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("NullResult", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markRetryCount.get());
            assertEquals(0, store.markDoneCount.get());
        }
    }

    @Test
    void retryAfterExceptionMarksRetryWithHandlerDelay() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markRetry(Connection conn, String eventId, java.time.Instant nextAt, String error) {
                super.markRetry(conn, eventId, nextAt, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("RetryAfterEx", event -> {
            throw new RetryAfterException(Duration.ofMinutes(5), "rate limited");
        });

        java.time.Instant before = java.time.Instant.now();
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(3)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .build()) {

            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("RetryAfterEx", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markRetryCount.get());
            assertEquals(0, store.markDeadCount.get());

            // Verify the delay value: nextAt should be ~now + 5min (300s)
            java.time.Instant nextAt = store.lastRetryNextAt.get();
            assertTrue(nextAt.isAfter(before.plusSeconds(299)),
                    "nextAt should be at least 299s after test start, was: " + nextAt);
            assertTrue(nextAt.isBefore(before.plusSeconds(305)),
                    "nextAt should be within 305s of test start, was: " + nextAt);
        }
    }

    @Test
    void retryAfterExceptionRespectsMaxAttempts() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("RetryAfterMaxed", event -> {
            throw new RetryAfterException(Duration.ofMinutes(5), "rate limited");
        });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(2)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .build()) {

            // attempts=1, maxAttempts=2 → nextAttempt (1+1=2) >= maxAttempts → DEAD
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("RetryAfterMaxed", "{}"), QueuedEvent.Source.HOT, 1));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeadCount.get());
            assertEquals(0, store.markRetryCount.get());
        }
    }

    @Test
    void defaultMarkDeferredFallsBackToMarkRetry() {
        // Minimal OutboxStore that does NOT override markDeferred — uses the default
        var retryCount = new AtomicInteger();
        io.outbox.spi.OutboxStore storeWithDefault = new io.outbox.spi.OutboxStore() {
            @Override
            public void insertNew(Connection conn, io.outbox.EventEnvelope event) {
            }

            @Override
            public int markDone(Connection conn, String eventId) {
                return 1;
            }

            @Override
            public int markRetry(Connection conn, String eventId, java.time.Instant nextAt, String error) {
                retryCount.incrementAndGet();
                return 1;
            }

            @Override
            public int markDead(Connection conn, String eventId, String error) {
                return 1;
            }

            @Override
            public java.util.List<io.outbox.model.OutboxEvent> pollPending(
                    Connection conn, java.time.Instant now, java.time.Duration skipRecent, int limit) {
                return java.util.List.of();
            }
        };

        java.time.Instant nextAt = java.time.Instant.now().plusSeconds(60);
        storeWithDefault.markDeferred(null, "test-event", nextAt);

        // Default falls back to markRetry (which increments attempts in real stores)
        assertEquals(1, retryCount.get());
    }

    @Test
    void runtimeExceptionInMarkDoneDoesNotCauseRetry() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDone(Connection conn, String eventId) {
                doneLatch.countDown();
                throw new RuntimeException("simulated store failure");
            }
        };

        AtomicInteger listenerCallCount = new AtomicInteger();
        var registry = new DefaultListenerRegistry()
                .register("RTETest", event -> {
                    listenerCallCount.incrementAndGet();
                    return DispatchResult.done();
                });

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("RTETest", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(doneLatch.await(3, TimeUnit.SECONDS));
            // Give time for any erroneous retry
            Thread.sleep(200);
            // Listener should be called exactly once — the RuntimeException from markDone
            // should be caught and logged, not propagate to handleFailure
            assertEquals(1, listenerCallCount.get());
            assertEquals(0, store.markRetryCount.get());
            assertEquals(0, store.markDeadCount.get());
        }
    }

    @Test
    void handlerReturningDeadMarksDeadImmediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("DeadResult", event -> DispatchResult.dead("business rejection"));

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("DeadResult", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeadCount.get());
            assertEquals(0, store.markDoneCount.get());
            assertEquals(0, store.markRetryCount.get());
            assertEquals(0, store.markDeferredCount.get());
        }
    }

    @Test
    void handlerReturningDeadWithoutReasonMarksDeadImmediately() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                latch.countDown();
                return 1;
            }
        };

        var registry = new DefaultListenerRegistry();
        registry.register("DeadNoReason", event -> DispatchResult.dead());

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("DeadNoReason", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDeadCount.get());
            assertEquals(0, store.markDoneCount.get());
        }
    }

    @Test
    void lambdaListenerBackwardCompat() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        var registry = new DefaultListenerRegistry()
                .register("LambdaCompat", event -> {
                    received.set(event.payloadJson());
                    latch.countDown();
                    return DispatchResult.done();
                });

        var store = new StubOutboxStore();
        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("LambdaCompat", "{\"ok\":true}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals("{\"ok\":true}", received.get());

            Thread.sleep(100);
            assertTrue(store.markDoneCount.get() > 0);
            assertEquals(0, store.markDeferredCount.get());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * A copy arriving while the first is still running is refused at the queue, lease untouched.
     *
     * <p>The complement of the two tests above, and the reason they must wait for the settled
     * marker rather than for the listener to start. Queueing it instead would be worse than
     * wasteful: with one worker the second copy is delivered as soon as the first releases,
     * against a row that is already DONE. The lease deliberately stays — the running copy will
     * mark the row terminal and clear it, and handing the lease back now would let another
     * instance claim a row that is actively being delivered.
     */
    @Test
    void aColdCopyOfAnEventStillRunningIsNotQueuedAtAll() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });
        var store = new StubOutboxStore();

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        var tracker = new DefaultInFlightTracker(60_000);
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(running.await(5, TimeUnit.SECONDS));

            assertTrue(d.enqueueCold(new QueuedEvent(
                            envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE)),
                    "reported accepted so the poller works through the rest of its batch");
            assertEquals(10, d.coldQueueRemainingCapacity(), "nothing was actually queued");
            assertEquals(0, store.releaseClaimCount.get(),
                    "the running copy still needs its lease");

            release.countDown();
            Thread.sleep(300);
            assertEquals(1, deliveries.get(), "one delivery, not one per copy");
        }
    }

    /**
     * The mirror of the test above: the poller won the race and the hot copy arrived second.
     *
     * <p>skip-recent-ms defaults to 0, so a poll can read a freshly committed row before its
     * afterCommit hook has run. Without a guard on the hot side, that ordering put one copy in
     * each queue and both were delivered. The cold copy here is still QUEUED, not running — a
     * workerless dispatcher makes that deterministic — because the queued-not-yet-acquired state
     * is exactly what the in-flight tracker alone cannot see.
     */
    @Test
    void aHotCopyOfAnEventAlreadyQueuedIsNotQueuedAgain() {
        var metrics = new CountingMetrics();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(100)
                .metrics(metrics)
                .build()) {
            assertTrue(d.enqueueCold(new QueuedEvent(
                    envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE)));

            assertTrue(d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0)),
                    "accepted — the event is already on its way, falling back would double it");
            assertEquals(1, metrics.suppressed.get(), "but recorded as the duplicate it is");
        }
    }

    /**
     * Concurrent enqueues of one event admit exactly one copy.
     *
     * <p>This is what makes the queued set a reservation rather than a hint. A contains-then-add
     * pair lets several callers — the poller thread and an after-commit thread, in practice — all
     * pass the check and all offer; without replay suppression the second copy then redelivers as
     * soon as the first settles. {@code Set.add} admits one caller atomically, so per round the
     * suppression count must be exactly callers-1.
     *
     * <p>Run over many rounds because the window is narrow: a single round caught the
     * contains-then-add version only about one time in three; fifty rounds make an escape
     * vanishingly unlikely while the atomic version stays deterministic.
     */
    @Test
    void concurrentEnqueuesOfOneEventAdmitExactlyOneCopy() throws Exception {
        int callers = 8;
        int rounds = 50;
        var metrics = new CountingMetrics();
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).hotQueueCapacity(100).coldQueueCapacity(100)
                .drainTimeoutMs(100)
                .metrics(metrics)
                .build()) {
            for (int round = 0; round < rounds; round++) {
                EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
                var start = new CountDownLatch(1);
                var done = new CountDownLatch(callers);
                for (int i = 0; i < callers; i++) {
                    final boolean hot = i % 2 == 0;
                    new Thread(() -> {
                        try {
                            start.await(5, TimeUnit.SECONDS);
                            if (hot) {
                                d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
                            } else {
                                d.enqueueCold(new QueuedEvent(
                                        envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }, "enqueue-" + i).start();
                }
                start.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS));
            }
            // callers-1 suppressions per round is the whole proof: each caller either wins the
            // reservation — at most one can, Set.add is atomic — or lands in the counter.
            assertEquals(rounds * (callers - 1), metrics.suppressed.get(),
                    "every caller but one winner per round is a suppressed duplicate");
        }
    }

    /**
     * The queued marker must cover the id until the tracker holds it — no gap between them.
     *
     * <p>Removing the marker before acquiring opens a window in which nothing covers the id: a
     * poll landing there queues a second copy, and once the first finishes and releases, that copy
     * delivers the same event again, in sequence. A tracker that blocks inside acquire() holds the
     * window open deterministically; a copy arriving while it is held must be refused.
     */
    @Test
    void theQueuedMarkerCoversTheIdUntilTheTrackerHoldsIt() throws Exception {
        CountDownLatch inAcquire = new CountDownLatch(1);
        CountDownLatch releaseAcquire = new CountDownLatch(1);
        var blockingTracker = new InFlightTracker() {
            final DefaultInFlightTracker delegate = new DefaultInFlightTracker(60_000);

            @Override
            public long acquire(String eventId) {
                inAcquire.countDown();
                try {
                    releaseAcquire.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return delegate.acquire(eventId);
            }

            @Override
            public boolean tryAcquire(String eventId) {
                return acquire(eventId) != NOT_ACQUIRED;
            }

            @Override
            public void release(String eventId) {
                delegate.release(eventId);
            }

            @Override
            public void release(String eventId, long token) {
                delegate.release(eventId, token);
            }

            @Override
            public void markSettled(String eventId, long token) {
                delegate.markSettled(eventId, token);
            }

            @Override
            public boolean isRunning(String eventId) {
                return delegate.isRunning(eventId);
            }

            @Override
            public boolean supportsIsRunning() {
                return true;
            }

            @Override
            public boolean isSettled(String eventId) {
                return delegate.isSettled(eventId);
            }

            @Override
            public boolean hasTtl() {
                return delegate.hasTtl();
            }
        };

        AtomicInteger deliveries = new AtomicInteger();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            deliveries.incrementAndGet();
            return DispatchResult.done();
        });
        var metrics = new CountingMetrics();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(blockingTracker)
                .suppressReplays(true)
                .metrics(metrics)
                .build()) {
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(inAcquire.await(5, TimeUnit.SECONDS), "the worker is now inside acquire()");

            // The exact instant the old ordering left uncovered.
            assertTrue(d.enqueueCold(new QueuedEvent(
                    envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE)));
            assertEquals(1, metrics.suppressed.get(),
                    "a copy arriving mid-acquire must be refused, not queued behind the first");
            assertEquals(10, d.coldQueueRemainingCapacity(), "nothing joined the queue");

            releaseAcquire.countDown();
            Thread.sleep(300);
            assertEquals(1, deliveries.get());
        }
    }

    /**
     * A tracker that throws must not leave the id blocked forever.
     *
     * <p>The marker is handed over in a finally for this reason: acquire-first without it would
     * strand the id in the queued set, and the enqueue guard would then refuse every future copy
     * of that event on sight.
     */
    @Test
    void aThrowingTrackerDoesNotBlockTheEventForever() throws Exception {
        var throwingOnce = new InFlightTracker() {
            final DefaultInFlightTracker delegate = new DefaultInFlightTracker(60_000);
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public long acquire(String eventId) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("tracker hiccup");
                }
                return delegate.acquire(eventId);
            }

            @Override
            public boolean tryAcquire(String eventId) {
                return acquire(eventId) != NOT_ACQUIRED;
            }

            @Override
            public void release(String eventId) {
                delegate.release(eventId);
            }

            @Override
            public void release(String eventId, long token) {
                delegate.release(eventId, token);
            }

            @Override
            public void markSettled(String eventId, long token) {
                delegate.markSettled(eventId, token);
            }

            @Override
            public boolean isRunning(String eventId) {
                return delegate.isRunning(eventId);
            }

            @Override
            public boolean supportsIsRunning() {
                return true;
            }

            @Override
            public boolean isSettled(String eventId) {
                return delegate.isSettled(eventId);
            }

            @Override
            public boolean hasTtl() {
                return delegate.hasTtl();
            }
        };
        CountDownLatch delivered = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            delivered.countDown();
            return DispatchResult.done();
        });
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(throwingOnce)
                .suppressReplays(true)
                .build()) {
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(200);      // first attempt dies inside acquire()

            assertTrue(d.enqueueCold(new QueuedEvent(
                            envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE)),
                    "the poller's next copy must get through");
            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "and deliver — the id was not left stranded in the queued set");
        }
    }

    private static void awaitSettled(DefaultInFlightTracker tracker, String eventId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!tracker.isSettled(eventId)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("event never settled: " + eventId);
            }
            Thread.sleep(5);
        }
    }

    /**
     * A close() that overlaps one in progress must wait for it. Returning early lets the second
     * caller go on to tear down what the workers still use — the DataSource, typically — while
     * they are draining, and their status writes fail.
     */
    @Test
    void anOverlappingCloseWaitsForTheDrainToFinish() throws Exception {
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch finishListener = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            listenerStarted.countDown();
            awaitQuietly(finishListener);
            return DispatchResult.done();
        });
        var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1)
                .drainTimeoutMs(5000)
                .build();
        try {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0));
            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));

            Thread first = new Thread(d::close);
            first.start();
            Thread.sleep(100);
            CountDownLatch secondReturned = new CountDownLatch(1);
            Thread second = new Thread(() -> {
                d.close();
                secondReturned.countDown();
            });
            second.start();

            assertFalse(secondReturned.await(300, TimeUnit.MILLISECONDS),
                    "the second close must not return while the first is still draining");
            finishListener.countDown();
            assertTrue(secondReturned.await(5, TimeUnit.SECONDS));
            first.join(5000);
            assertFalse(first.isAlive());
        } finally {
            finishListener.countDown();
            d.close();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ConnectionProvider stubCp() {
        return () -> (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if ("setAutoCommit".equals(method.getName())) {
                        return null;
                    }
                    return null;
                });
    }

    // ── Fair polling ────────────────────────────────────────────────

    /**
     * A worker must not block on one queue while the other has work waiting.
     *
     * <p>The weighted round-robin used to reach for the hot queue with a 50ms blocking poll, so
     * whenever the hot queue was idle — the whole of any backlog drain — 2 of every 3 cycles paid
     * 50ms of dead wait before touching the cold queue. Measured at the time: 30 cold events took
     * 1072ms on one worker, a ceiling of ~28 events/s that no amount of listener or database speed
     * could lift, and which reads from the outside as "the workers are saturated but nothing else
     * is". The threshold below sits far under that and far above the ~50ms it now takes.
     */
    @Test
    void coldWorkIsNotDelayedByAnIdleHotQueue() throws Exception {
        int events = 30;
        CountDownLatch latch = new CountDownLatch(events);
        var registry = new DefaultListenerRegistry()
                .register("Fair", event -> {
                    latch.countDown();
                    return DispatchResult.done();
                });

        try (var d = newDispatcher(1, 100, 100, registry, new StubOutboxStore())) {
            for (int i = 0; i < events; i++) {
                d.enqueueCold(new QueuedEvent(EventEnvelope.ofJson("Fair", "{}"),
                        QueuedEvent.Source.COLD, 0));
            }
            long startNanos = System.nanoTime();
            assertTrue(latch.await(10, TimeUnit.SECONDS), "all cold events should be dispatched");
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            assertTrue(elapsedMs < 500,
                    "draining " + events + " cold events took " + elapsedMs + "ms; the hot queue is "
                            + "empty, so the workers should never be waiting on it");
        }
    }

    // ── Hot-path breaker ────────────────────────────────────────────

    /**
     * Ages the head of the queue by stamping {@code enqueuedAtNanos} in the past, rather than
     * sleeping: the breaker reads head age, so backdating the head is the same input with none of
     * the wall-clock flakiness.
     */
    private static QueuedEvent agedHot(String type, long ageMs) {
        return new QueuedEvent(EventEnvelope.ofJson(type, "{}"), QueuedEvent.Source.HOT, 0,
                System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(ageMs));
    }

    @Test
    void hotBreakerDisabledByDefault() {
        try (var d = newDispatcher(0, 10, 10)) {
            assertTrue(d.enqueueHot(agedHot("A", 60_000)));
            assertTrue(d.enqueueHot(agedHot("B", 0)),
                    "hotTripMs defaults to 0, so an ancient head must not block anything");
        }
    }

    @Test
    void hotBreakerTripsOnceHeadIsOlderThanThreshold() {
        try (var d = newDispatcherWithTrip(10, 500)) {
            assertTrue(d.enqueueHot(agedHot("head", 900)), "first offer fills the empty queue");
            assertFalse(d.enqueueHot(agedHot("next", 0)), "head is 900ms old, over the 500ms trip");
        }
    }

    @Test
    void hotBreakerStaysClosedWhileHeadIsYoung() {
        try (var d = newDispatcherWithTrip(10, 500)) {
            assertTrue(d.enqueueHot(agedHot("head", 100)));
            assertTrue(d.enqueueHot(agedHot("next", 0)));
        }
    }

    /**
     * A breaker that never closes again would silently disable the hot path for the life of the
     * pod, so recovery is worth a real end-to-end test rather than trusting the branch by eye.
     *
     * <p>Shape: one worker, held inside the listener so it cannot drain. That leaves a second,
     * back-dated event sitting as the queue head, which is what trips the breaker. Releasing the
     * hold lets the worker consume it; an empty queue reads as head age 0, and the next offer is
     * accepted again.
     */
    @Test
    void hotBreakerRecoversOnceTheQueueDrains() throws Exception {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register("Hold", event -> {
            listenerEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });
        registry.register(TAIL, event -> {
            secondDelivered.countDown();
            return DispatchResult.done();
        });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000).hotTripMs(500).build()) {

            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("Hold", "{}"), QueuedEvent.Source.HOT, 0));
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS), "worker must be occupied");

            assertTrue(d.enqueueHot(agedHot(TAIL, 900)), "queue is empty, so this offer is accepted");
            assertFalse(d.enqueueHot(agedHot(TAIL, 0)), "head is now 900ms old: tripped");

            release.countDown();
            assertTrue(secondDelivered.await(5, TimeUnit.SECONDS), "queue drains");

            assertTrue(d.enqueueHot(agedHot(TAIL, 0)), "empty queue: head age 0, breaker closed again");
        }
    }

    /**
     * Through {@code DispatcherWriterHook}, i.e. the way production actually enqueues. My first
     * version of this only called {@code enqueueHot} directly, which bypasses the hook and made the
     * two counters look independent — they are not. The hook counts EVERY refusal as dropped
     * because a boolean return cannot carry the reason, so tripped is a subset of dropped. Charting
     * them as siblings double-counts every trip; this test pins the relationship down.
     */
    @Test
    void trippedIsASubsetOfDroppedWhenEnqueuedThroughTheHook() {
        CountingMetrics metrics = new CountingMetrics();
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000).hotTripMs(500).metrics(metrics).build()) {
            var hook = new DispatcherWriterHook(d, metrics);

            d.enqueueHot(agedHot("head", 900));
            hook.afterCommit(List.of(EventEnvelope.ofJson(TAIL, "{}")));

            assertEquals(1, metrics.tripped.get());
            assertEquals(1, metrics.dropped.get(),
                    "the hook counts the trip as a drop too — dropped is the total, not the "
                            + "full-queue-only count");
            assertEquals(0, metrics.enqueued.get());
        }
    }

    /** Called directly (not via the hook), where only the dispatcher's own counter fires. */
    @Test
    void hotBreakerCountsTheTripOnEnqueueHot() {
        CountingMetrics metrics = new CountingMetrics();
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).hotQueueCapacity(1).coldQueueCapacity(10)
                .drainTimeoutMs(1000).hotTripMs(500).metrics(metrics).build()) {
            d.enqueueHot(agedHot("head", 900));
            d.enqueueHot(agedHot("tripped", 0));
            assertEquals(1, metrics.tripped.get(), "refused for being slow");
            assertEquals(0, metrics.dropped.get(),
                    "enqueueHot itself never counts a drop — the hook does, see "
                            + "trippedIsASubsetOfDroppedWhenEnqueuedThroughTheHook");
        }
    }

    @Test
    void builderRejectsNegativeHotTripMs() {
        assertThrows(IllegalArgumentException.class, () -> OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).hotTripMs(-1).build());
    }

    private static final class CountingMetrics implements io.outbox.spi.MetricsExporter {
        final AtomicInteger tripped = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger();
        final AtomicInteger suppressed = new AtomicInteger();
        final AtomicInteger enqueued = new AtomicInteger();
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger dead = new AtomicInteger();

        @Override public void incrementHotTripped() {
            tripped.incrementAndGet();
        }

        @Override public void incrementHotDropped() {
            dropped.incrementAndGet();
        }

        @Override public void incrementDispatchSuppressed() {
            suppressed.incrementAndGet();
        }

        @Override public void incrementHotEnqueued() {
            enqueued.incrementAndGet();
        }

        @Override public void incrementColdEnqueued() {
        }

        @Override public void incrementDispatchSuccess() {
            success.incrementAndGet();
        }

        @Override public void incrementDispatchFailure() {
        }

        @Override public void incrementDispatchDead() {
            dead.incrementAndGet();
        }

        @Override public void recordQueueDepths(int hotDepth, int coldDepth) {
        }

        @Override public void recordOldestLagMs(long lagMs) {
        }
    }

    // ── Replay suppression (settled events stay tracked until TTL) ──

    @Test
    void suppressReplaysRequiresATrackerWithTtl() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                OutboxDispatcher.builder()
                        .connectionProvider(stubCp())
                        .outboxStore(new StubOutboxStore())
                        .listenerRegistry(new DefaultListenerRegistry())
                        .workerCount(0)
                        .inFlightTracker(new DefaultInFlightTracker())
                        .suppressReplays(true)
                        .build());
        assertTrue(e.getMessage().contains("TTL"),
                "without expiry a settled entry is never reclaimed and the event can never run again");
    }

    @Test
    void settledEventIsNotDeliveredASecondTime() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            first.countDown();
            return DispatchResult.done();
        });

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = suppressing(registry, 60_000)) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS));

            // The poller's copy of the same row, arriving after the hot copy already settled —
            // the exact sequence behind routine hot/cold duplicate deliveries.
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(300);
            assertEquals(1, deliveries.get(), "second copy of a settled event must not run");
        }
    }

    @Test
    void retryingEventIsReleasedImmediatelyAndCanRunAgain() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch twice = new CountDownLatch(2);
        var registry = new DefaultListenerRegistry();
        registry.register("Flaky", event -> {
            deliveries.incrementAndGet();
            first.countDown();
            twice.countDown();
            throw new IllegalStateException("boom");
        });

        EventEnvelope envelope = EventEnvelope.ofJson("Flaky", "{}");
        // retry.base-delay-ms is 200ms in production; holding a RETRY entry for the TTL would
        // stretch every retry interval to the TTL instead. This is the regression that guards it.
        //
        // The second copy is enqueued only after the first has finished, which is what a retry
        // actually looks like: the poller re-reads the row once available_at has passed, with
        // nothing of that event queued or running. Enqueueing both at once — what this used to do —
        // is two simultaneous copies of one event, and the dispatcher now refuses the second on
        // sight, so it would prove nothing about the TTL either way.
        try (var d = suppressing(registry, 60_000)) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS), "the first attempt should run");
            Thread.sleep(200);          // let the RETRY outcome settle

            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 1, System.nanoTime(), LEASE));
            assertTrue(twice.await(5, TimeUnit.SECONDS),
                    "a failed event must stay dispatchable, TTL notwithstanding");
            assertEquals(2, deliveries.get());
        }
    }

    @Test
    void suppressedDispatchIsCounted() throws Exception {
        CountingMetrics metrics = new CountingMetrics();
        CountDownLatch first = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            first.countDown();
            return DispatchResult.done();
        });

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .metrics(metrics)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(300);
            assertEquals(1, metrics.suppressed.get(), "the duplicate that was NOT delivered");
        }
    }

    @Test
    void settledEventRunsAgainOnceTheTtlExpires() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            twice.countDown();
            return DispatchResult.done();
        });

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        // Suppression is a bounded window, not a permanent record. A 1ms TTL proves the entry is
        // reclaimed rather than leaked — the property that makes this safe to enable at all.
        try (var d = suppressing(registry, 1)) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            Thread.sleep(50);
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(twice.await(5, TimeUnit.SECONDS));
            assertEquals(2, deliveries.get());
        }
    }

    /**
     * withConnection logs and swallows write failures, so "the listener returned Done" is not the
     * same as "the row is DONE". If suppression trusted the listener instead of the write, a
     * swallowed markDone would leave the row PENDING in the database while the tracker refused
     * every redelivery — no delivery at all until the TTL expired.
     */
    @Test
    void aSwallowedTerminalWriteMustNotSuppressRedelivery() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            twice.countDown();
            return DispatchResult.done();
        });
        var brokenStore = new StubOutboxStore() {
            @Override
            public int markDone(Connection conn, String eventId) {
                throw new IllegalStateException("DB write lost");
            }
        };

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(brokenStore)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            Thread.sleep(200);
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));

            assertTrue(twice.await(5, TimeUnit.SECONDS),
                    "the row is still PENDING, so the event must remain deliverable");
            assertEquals(2, deliveries.get());
        }
    }

    /**
     * An operator replay lands the event back in the queue while its settled marker is still live.
     * Suppression then drops the dispatch — correct — but the poller has already leased the row, so
     * without handing that lease back the replay silently does nothing until the lock timeout. Five
     * minutes of "I pressed replay and nothing happened" is the failure this pins down.
     */
    @Test
    void aSuppressedColdDispatchHandsBackTheLease() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            first.countDown();
            return DispatchResult.done();
        });
        var store = new StubOutboxStore();

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        var tracker = new DefaultInFlightTracker(60_000);
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            // Settled, not merely started: a copy arriving while the first is still running is the
            // other case entirely, and there the lease is deliberately kept.
            awaitSettled(tracker, envelope.eventId());

            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(300);
            assertEquals(1, store.releaseClaimCount.get(),
                    "the poller's lease must not outlive an abandoned dispatch");
        }
    }

    /**
     * The dangerous half of releasing a lease. While a listener is STILL RUNNING here, that lease is
     * the only thing keeping other nodes off the row — their trackers know nothing about this JVM.
     * Handing it back mid-flight would invite the cross-JVM duplicate the whole branch exists to
     * remove, and it would be self-inflicted: worse than the stranding it was meant to repair.
     *
     * <p>So a suppressed dispatch may only release against a marker that is known to be SETTLED.
     * "Another worker has it right now" and "this finished a moment ago" both fail tryAcquire, and
     * they demand opposite handling.
     */
    @Test
    void aSuppressedColdDispatchKeepsTheLeaseWhileTheEventIsStillInFlight() throws Exception {
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            listenerEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });
        var store = new StubOutboxStore();

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(2).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS), "hot copy is inside the listener");

            // Second worker picks this up and finds the event already acquired — but NOT settled.
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(300);
            assertEquals(0, store.releaseClaimCount.get(),
                    "in-flight, so the lease must stay: it is what keeps other nodes off the row");

            release.countDown();
        }
    }

    /**
     * A replayed event must become deliverable again on the NEXT poll, not after the TTL. Releasing
     * the lease alone is not enough: the marker survives, so every following poll claims the row,
     * suppresses it and releases again — a DB round-trip per poll for the whole TTL, and the replay
     * still does nothing until it expires.
     *
     * <p>releaseClaim only matches rows still awaiting delivery, so a hit is proof the row came back
     * after the marker was written. That is what licenses dropping it.
     */
    @Test
    void aStaleMarkerIsDroppedSoAReplayedEventRunsAgain() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            twice.countDown();
            return DispatchResult.done();
        });
        // Stands in for a row that was reset: still awaiting delivery, so releaseClaim matches it.
        var replayed = new StubOutboxStore() {
            @Override
            public int releaseClaim(java.sql.Connection conn, String eventId, String claimOwner,
                                    java.time.Instant claimedAt) {
                releaseClaimCount.incrementAndGet();
                return 1;
            }
        };

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(replayed)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            Thread.sleep(200);

            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(200);
            assertEquals(1, deliveries.get(), "that copy is still suppressed — the marker was live");

            // The poll after the repair, well inside the 60s TTL.
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(twice.await(5, TimeUnit.SECONDS),
                    "marker was proven stale and dropped, so the replay must now run");
        }
    }

    /**
     * Dropping a stale marker must not end whatever acquired the event after it. The lease release
     * is a database round-trip; the marker can expire during it, and the freed row can be claimed
     * and acquired again. An id-keyed release at that point removed the newer, running entry and
     * let a third copy in alongside it.
     */
    @Test
    void droppingAStaleMarkerDoesNotEndANewerAcquisition() throws Exception {
        // Short enough for the marker to expire during the stalled release, long enough that the
        // newer acquisition is still inside its TTL when asserted (isRunning honours expiry too).
        var tracker = new DefaultInFlightTracker(500);
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        String eventId = envelope.eventId();
        tracker.markSettled(eventId, tracker.acquire(eventId));

        CountDownLatch releasing = new CountDownLatch(1);
        CountDownLatch finishRelease = new CountDownLatch(1);
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch finishListener = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int releaseClaim(java.sql.Connection conn, String id, String claimOwner,
                                    java.time.Instant claimedAt) {
                releasing.countDown();
                awaitQuietly(finishRelease);
                return 1;
            }
        };
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            listenerStarted.countDown();
            awaitQuietly(finishListener);
            return DispatchResult.done();
        });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(2).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .suppressReplays(true)
                .build()) {
            // Suppressed against the live marker; its lease release then stalls in the database.
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(releasing.await(5, TimeUnit.SECONDS));

            // The marker expires meanwhile, and the next poll's copy acquires and starts running.
            Thread.sleep(600);
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS));

            finishRelease.countDown();
            Thread.sleep(100);
            assertTrue(tracker.isRunning(eventId), "the stale cleanup must leave the running entry alone");
            assertEquals(InFlightTracker.NOT_ACQUIRED, tracker.acquire(eventId),
                    "a third copy must not be admitted while the second is still running");
            finishListener.countDown();
        } finally {
            finishRelease.countDown();
            finishListener.countDown();
        }
    }

    /**
     * The stale cleanup must drop only the marker it saw. If its database call returns after the
     * freed row was claimed, delivered and settled again, a plain "is it settled" check deleted the
     * newer marker, and a late copy then ran the listener a second time.
     */
    @Test
    void aStaleMarkerCleanupLeavesANewerMarkerAlone() throws Exception {
        var tracker = new DefaultInFlightTracker(300);
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        String eventId = envelope.eventId();
        tracker.markSettled(eventId, tracker.acquire(eventId));

        CountDownLatch releasing = new CountDownLatch(1);
        CountDownLatch finishRelease = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int releaseClaim(java.sql.Connection conn, String id, String claimOwner,
                                    java.time.Instant claimedAt) {
                releasing.countDown();
                awaitQuietly(finishRelease);
                return 1;
            }
        };
        AtomicInteger deliveries = new AtomicInteger();
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            return DispatchResult.done();
        });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(2).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .suppressReplays(true)
                .build()) {
            // Suppressed against the live marker; its lease release stalls in the database.
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(releasing.await(5, TimeUnit.SECONDS));

            // The marker expires, the next poll's copy runs and settles: a newer marker.
            Thread.sleep(400);
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(deliveries.get() == 1 && tracker.isSettled(eventId)) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(tracker.isSettled(eventId));

            finishRelease.countDown();
            Thread.sleep(100);
            assertTrue(tracker.isSettled(eventId), "the stale cleanup must leave the newer marker alone");

            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            Thread.sleep(200);
            assertEquals(1, deliveries.get(), "a late copy is refused by the newer marker");
        } finally {
            finishRelease.countDown();
        }
    }

    /**
     * An enqueue that read "not running" just before the earlier copy handed off from the queue to
     * the tracker must still be refused. Checking the tracker before reserving left that gap: the
     * reservation then succeeded behind a listener that was already running, and the second copy
     * delivered as soon as the first released.
     */
    @Test
    void anEnqueueRacingTheHandoffDoesNotQueueASecondCopy() throws Exception {
        var delegate = new DefaultInFlightTracker();
        CountDownLatch readTaken = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        var tracker = new InFlightTracker() {
            @Override
            public boolean tryAcquire(String eventId) {
                return delegate.tryAcquire(eventId);
            }

            @Override
            public long acquire(String eventId) {
                return delegate.acquire(eventId);
            }

            @Override
            public void release(String eventId) {
                delegate.release(eventId);
            }

            @Override
            public void release(String eventId, long token) {
                delegate.release(eventId, token);
            }

            @Override
            public boolean isRunning(String eventId) {
                boolean running = delegate.isRunning(eventId);
                if ("racing-enqueue".equals(Thread.currentThread().getName())) {
                    readTaken.countDown();
                    awaitQuietly(resume);
                }
                return running;
            }
        };
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch finishListener = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            deliveries.incrementAndGet();
            listenerStarted.countDown();
            awaitQuietly(finishListener);
            return DispatchResult.done();
        });

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .build()) {
            Thread racing = new Thread(
                    () -> d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0)), "racing-enqueue");
            racing.start();
            assertTrue(readTaken.await(5, TimeUnit.SECONDS));

            // The other copy arrives while the racing enqueue is paused on its tracker read. With
            // the old ordering it is queued, handed off and running by the time the race resumes.
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            listenerStarted.await(500, TimeUnit.MILLISECONDS);

            resume.countDown();
            racing.join(5000);
            finishListener.countDown();
            Thread.sleep(300);
            assertEquals(1, deliveries.get(), "exactly one copy may be queued while the other runs");
        } finally {
            resume.countDown();
            finishListener.countDown();
        }
    }

    /**
     * A tracker that throws during an enqueue must not strand the reservation. Otherwise the id
     * stays reserved with nothing queued or running, and every later copy is reported accepted
     * and silently dropped — the event never delivers.
     */
    @Test
    void aTrackerFailureDuringEnqueueDoesNotStrandTheEvent() throws Exception {
        var delegate = new DefaultInFlightTracker();
        AtomicInteger failuresLeft = new AtomicInteger(1);
        var tracker = new InFlightTracker() {
            @Override
            public boolean tryAcquire(String eventId) {
                return delegate.tryAcquire(eventId);
            }

            @Override
            public void release(String eventId) {
                delegate.release(eventId);
            }

            @Override
            public boolean isRunning(String eventId) {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("temporary tracker failure");
                }
                return delegate.isRunning(eventId);
            }
        };
        CountDownLatch delivered = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            delivered.countDown();
            return DispatchResult.done();
        });

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .build()) {
            assertThrows(IllegalStateException.class,
                    () -> d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0)));

            assertTrue(d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0)));
            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "the next poll's copy must be queued and delivered, not dropped as a duplicate");
        }
    }

    @Test
    void aSuppressedHotDispatchDoesNotTouchTheDatabase() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            first.countDown();
            return DispatchResult.done();
        });
        var store = new StubOutboxStore();

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS));

            // A hot copy was never claimed, so there is no lease to hand back and no reason to spend
            // a round-trip finding that out.
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            Thread.sleep(300);
            assertEquals(0, store.releaseClaimCount.get());
        }
    }

    /**
     * A queued copy can outlive its lease: the timeout expires, another instance claims the row and
     * starts delivering it. Releasing by event id alone would clear THAT instance's lease and hand
     * the event out twice — the duplicate this machinery exists to stop, caused by the machinery.
     * So the release carries the owner it was claimed under, and the store matches on it.
     */
    @Test
    void aReleaseNeverClearsALeaseTakenOverBySomebodyElse() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            first.countDown();
            return DispatchResult.done();
        });
        AtomicReference<String> releasedFor = new AtomicReference<>();
        var store = new StubOutboxStore() {
            @Override
            public int releaseClaim(java.sql.Connection conn, String eventId, String claimOwner,
                                    java.time.Instant claimedAt) {
                releasedFor.set(claimOwner);
                return 0;
            }
        };

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        var tracker = new DefaultInFlightTracker(60_000);
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(tracker)
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            // The latch fires as the listener STARTS. This test is about the copy that arrives
            // after the first one settled, so wait for the settled marker rather than for the
            // listener — otherwise it is the still-in-flight case, where the lease must be kept.
            awaitSettled(tracker, envelope.eventId());

            d.enqueueCold(new QueuedEvent(
                    envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            Thread.sleep(300);
            assertEquals("pod-a", releasedFor.get(),
                    "the release must name the lease it is undoing, so the store can refuse to "
                            + "clear a newer one");
        }
    }

    @Test
    void aColdCopyWithNoLeaseIsNeverReleased() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry();
        registry.register(ONCE, event -> {
            first.countDown();
            return DispatchResult.done();
        });
        var store = new StubOutboxStore();

        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1).hotQueueCapacity(10).coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(5, TimeUnit.SECONDS));

            // Claim locking off: nothing was leased, so there is nothing to hand back.
            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0));
            Thread.sleep(300);
            assertEquals(0, store.releaseClaimCount.get());
        }
    }

    private static OutboxDispatcher suppressing(DefaultListenerRegistry registry, long ttlMs) {
        return OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1)
                .hotQueueCapacity(10)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(ttlMs))
                .suppressReplays(true)
                .build();
    }

    private static OutboxDispatcher newDispatcherWithTrip(int hot, long tripMs) {
        return OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .hotQueueCapacity(hot)
                .coldQueueCapacity(10)
                .drainTimeoutMs(1000)
                .hotTripMs(tripMs)
                .build();
    }

    private static OutboxDispatcher newDispatcher(int workers, int hot, int cold) {
        return newDispatcher(workers, hot, cold, new DefaultListenerRegistry(), new StubOutboxStore());
    }

    private static OutboxDispatcher newDispatcher(int workers, int hot, int cold,
                                                  DefaultListenerRegistry registry, StubOutboxStore store) {
        return OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(workers)
                .hotQueueCapacity(hot)
                .coldQueueCapacity(cold)
                .drainTimeoutMs(1000)
                .build();
    }

    // ── Listener misbehaviour: Errors and leaked interrupts ──────────

    /**
     * An Error used to skip the RETRY/DEAD write entirely: the row kept its attempt count and was
     * redelivered forever. It now spends the retry budget like an exception, interceptors unwind
     * once with it as the cause, and the worker carries on.
     */
    @Test
    void listenerErrorIsRetriedAndInterceptorsUnwind() throws Exception {
        CountDownLatch tailDone = new CountDownLatch(1);
        List<Exception> afterErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
        var store = new StubOutboxStore();
        var registry = new DefaultListenerRegistry()
                .register("Broken", event -> {
                    throw new NoClassDefFoundError("missing/Dependency");
                })
                .register(TAIL, event -> {
                    tailDone.countDown();
                    return DispatchResult.done();
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(3)
                .drainTimeoutMs(1000)
                .interceptor(new EventInterceptor() {
                    @Override
                    public void afterDispatch(EventEnvelope event, Exception error) {
                        if ("Broken".equals(event.eventType())) {
                            afterErrors.add(error);
                        }
                    }
                })
                .build()) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("Broken", "{}"), QueuedEvent.Source.HOT, 0));
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(TAIL, "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(tailDone.await(3, TimeUnit.SECONDS), "the worker must survive the Error");
        }

        assertEquals(1, store.markRetryCount.get());
        assertEquals(0, store.markDeadCount.get());
        assertEquals(1, afterErrors.size(), "afterDispatch runs once for the failed event");
        assertTrue(afterErrors.get(0) instanceof RecoverableException);
        assertTrue(afterErrors.get(0).getCause() instanceof NoClassDefFoundError);
    }

    /**
     * An Error from one afterDispatch is logged like an exception: the unwind runs every hook
     * exactly once and the listener's own result still decides the status.
     */
    @Test
    void afterDispatchErrorDoesNotRerunHooksOrChangeTheOutcome() {
        Map<String, AtomicInteger> afterCalls = new ConcurrentHashMap<>();
        var store = new StubOutboxStore();
        var registry = new DefaultListenerRegistry()
                .register(ONCE, event -> DispatchResult.done());

        var builder = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .drainTimeoutMs(1000);
        for (String name : List.of("first", "broken", "last")) {
            builder.interceptor(new EventInterceptor() {
                @Override
                public void afterDispatch(EventEnvelope event, Exception error) {
                    afterCalls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                    if ("broken".equals(name)) {
                        throw new AssertionError("broken hook");
                    }
                }
            });
        }
        var d = builder.build();
        d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0));
        d.close(); // drains the queue and waits for the worker

        for (String name : List.of("first", "broken", "last")) {
            assertEquals(1, afterCalls.getOrDefault(name, new AtomicInteger()).get(), name + ".afterDispatch call count");
        }
        assertEquals(1, store.markDoneCount.get());
        assertEquals(0, store.markRetryCount.get());
    }

    @Test
    void listenerErrorGoesDeadOnceAttemptsAreSpent() throws Exception {
        CountDownLatch dead = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                dead.countDown();
                return 1;
            }
        };
        var registry = new DefaultListenerRegistry()
                .register("Broken", event -> {
                    throw new StackOverflowError();
                });

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(2)
                .drainTimeoutMs(1000)
                .build()) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("Broken", "{}"), QueuedEvent.Source.HOT, 1));

            assertTrue(dead.await(3, TimeUnit.SECONDS));
        }
        assertEquals(0, store.markRetryCount.get());
    }

    /**
     * A listener that re-interrupts after catching InterruptedException returns with the flag set.
     * That used to end workerLoop for good, and with one worker nothing was ever delivered again.
     */
    @Test
    void listenerLeavingTheThreadInterruptedDoesNotStopTheWorker() throws Exception {
        CountDownLatch tailDone = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry()
                .register(ONCE, event -> {
                    Thread.currentThread().interrupt();
                    return DispatchResult.done();
                })
                .register(TAIL, event -> {
                    tailDone.countDown();
                    return DispatchResult.done();
                });

        try (var d = newDispatcher(1, 10, 10, registry, new StubOutboxStore())) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0));
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(TAIL, "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(tailDone.await(3, TimeUnit.SECONDS), "the worker stopped after a leaked interrupt");
        }
    }

    /**
     * The other half: clearing a leaked interrupt must not swallow the one a forced close sends.
     * The listener re-interrupts after shutdownNow wakes it, and the queued tail must then stay put.
     */
    @Test
    void forcedCloseInterruptIsNotSwallowed() throws Exception {
        CountDownLatch blocking = new CountDownLatch(1);
        AtomicInteger tailRuns = new AtomicInteger();
        var registry = new DefaultListenerRegistry()
                .register(ONCE, event -> {
                    blocking.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return DispatchResult.done();
                })
                .register(TAIL, event -> {
                    tailRuns.incrementAndGet();
                    return DispatchResult.done();
                });

        var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(registry)
                .workerCount(1)
                .drainTimeoutMs(100)
                .build();
        d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0));
        d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(TAIL, "{}"), QueuedEvent.Source.HOT, 0));
        assertTrue(blocking.await(3, TimeUnit.SECONDS));

        d.close();

        assertEquals(0, tailRuns.get(), "a forced stop must not go on to dispatch the queued tail");
    }

    /** A DONE or DEAD that never reached the database must not be counted as one. */
    @Test
    void terminalMetricsCountOnlyWritesThatLanded() throws Exception {
        CountDownLatch writes = new CountDownLatch(4);
        var store = new StubOutboxStore() {
            @Override
            public int markDone(Connection conn, String eventId) {
                writes.countDown();
                if (eventId.startsWith("lost")) {
                    throw new IllegalStateException("database down");
                }
                return super.markDone(conn, eventId);
            }

            @Override
            public int markDead(Connection conn, String eventId, String error) {
                writes.countDown();
                throw new IllegalStateException("database down");
            }
        };
        var registry = new DefaultListenerRegistry()
                .register("Done", event -> DispatchResult.done())
                .register("Dead", event -> DispatchResult.dead("bad"))
                .register("Unrecoverable", event -> {
                    throw new UnrecoverableException("bad");
                });
        CountingMetrics metrics = new CountingMetrics();

        var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .metrics(metrics)
                .drainTimeoutMs(1000)
                .build();
        d.enqueueHot(hotWithId("Done", "kept"));
        d.enqueueHot(hotWithId("Done", "lost-done"));
        d.enqueueHot(hotWithId("Dead", "lost-dead"));
        d.enqueueHot(hotWithId("Unrecoverable", "lost-unrecoverable"));
        assertTrue(writes.await(3, TimeUnit.SECONDS));
        d.close(); // waits for the worker, so the last counter update has happened

        assertEquals(1, metrics.success.get());
        assertEquals(0, metrics.dead.get());
    }

    private static QueuedEvent hotWithId(String type, String eventId) {
        return new QueuedEvent(EventEnvelope.builder(type).eventId(eventId).payloadJson("{}").build(),
                QueuedEvent.Source.HOT, 0);
    }

    // ── Claim fencing ───────────────────────────────────────────────

    /**
     * Every status write names the authority it runs under: a claimed copy its exact lease, a hot
     * copy the dispatcher's claim owner with no lease (its writer stamp). That is what lets the
     * store refuse a write from a copy whose lease has since been taken over.
     */
    @Test
    void statusWritesCarryTheCopysClaim() throws Exception {
        Map<String, String> ownerByEvent = new ConcurrentHashMap<>();
        CountDownLatch writes = new CountDownLatch(5);
        var store = new StubOutboxStore() {
            @Override
            public int markDone(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
                return record(eventId, claimOwner, claimedAt);
            }

            @Override
            public int markRetry(Connection conn, String eventId, Instant nextAt, String error,
                                 String claimOwner, Instant claimedAt) {
                return record(eventId, claimOwner, claimedAt);
            }

            @Override
            public int markDead(Connection conn, String eventId, String error, String claimOwner, Instant claimedAt) {
                return record(eventId, claimOwner, claimedAt);
            }

            @Override
            public int markDeferred(Connection conn, String eventId, Instant nextAt, String claimOwner,
                                    Instant claimedAt) {
                return record(eventId, claimOwner, claimedAt);
            }

            private int record(String eventId, String claimOwner, Instant claimedAt) {
                ownerByEvent.put(eventId, claimOwner + "@" + claimedAt);
                writes.countDown();
                return 1;
            }
        };
        var registry = new DefaultListenerRegistry()
                .register("Done", event -> DispatchResult.done())
                .register("Deferred", event -> DispatchResult.retryAfter(Duration.ofSeconds(1)))
                .register("Dead", event -> DispatchResult.dead("no"))
                .register("Flaky", event -> {
                    throw new IllegalStateException("boom");
                });
        QueuedEvent.ClaimLease otherLease = new QueuedEvent.ClaimLease("pod-z", LEASE.claimedAt());

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .maxAttempts(3)
                .claimOwner("pod-a")
                .drainTimeoutMs(1000)
                .build()) {
            d.enqueueHot(hotWithId("Done", "hot-done"));
            d.enqueueHot(hotWithId("Deferred", "hot-deferred"));
            d.enqueueHot(hotWithId("Dead", "hot-dead"));
            d.enqueueHot(hotWithId("Flaky", "hot-retry"));
            d.enqueueCold(new QueuedEvent(EventEnvelope.builder("Done").eventId("cold-done").payloadJson("{}").build(),
                    QueuedEvent.Source.COLD, 0, System.nanoTime(), otherLease));
            assertTrue(writes.await(3, TimeUnit.SECONDS));
        }

        assertEquals(Map.of(
                "hot-done", "pod-a@null",
                "hot-deferred", "pod-a@null",
                "hot-dead", "pod-a@null",
                "hot-retry", "pod-a@null",
                "cold-done", "pod-z@" + LEASE.claimedAt()), ownerByEvent);
    }

    /**
     * A write that matched no row — already terminal, or fenced out because another instance holds
     * the row now — was not settled here. It must not leave a replay-suppression marker (that would
     * suppress this instance's next copy of an event it never settled) or count as DONE.
     */
    @Test
    void aWriteThatMatchesNoRowIsNotTreatedAsSettled() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(2);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            deliveries.incrementAndGet();
            first.countDown();
            second.countDown();
            return DispatchResult.done();
        });
        var store = new StubOutboxStore() {
            @Override
            public int markDone(Connection conn, String eventId) {
                return 0;
            }

            @Override
            public int markDone(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
                return 0;
            }
        };
        CountingMetrics metrics = new CountingMetrics();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                .metrics(metrics)
                .drainTimeoutMs(1000)
                .inFlightTracker(new DefaultInFlightTracker(60_000))
                .suppressReplays(true)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(first.await(3, TimeUnit.SECONDS));
            Thread.sleep(300); // let the first copy finish its write and leave the tracker

            d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), LEASE));
            assertTrue(second.await(3, TimeUnit.SECONDS), "the event was never settled, so it must run again");
        }
        assertEquals(0, metrics.success.get());
    }

    // ── Claim hand-over ─────────────────────────────────────────────

    private static final QueuedEvent.ClaimLease L1 = LEASE;
    private static final QueuedEvent.ClaimLease L2 =
            new QueuedEvent.ClaimLease("pod-a", LEASE.claimedAt().plusSeconds(600));
    private static final QueuedEvent.ClaimLease L3 =
            new QueuedEvent.ClaimLease("pod-a", LEASE.claimedAt().plusSeconds(1200));

    /** Records each fenced status write as "eventId:owner@claimedAt" and each release as "owner@claimedAt". */
    private static class FencingStore extends StubOutboxStore {
        final List<String> writes = new CopyOnWriteArrayList<>();
        final List<String> releases = new CopyOnWriteArrayList<>();

        @Override
        public int markDone(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
            writes.add(eventId + ":" + claimOwner + "@" + claimedAt);
            return 1;
        }

        @Override
        public int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
            releases.add(claimOwner + "@" + claimedAt);
            return 1;
        }

        void awaitWrites(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (writes.size() < count) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("expected " + count + " writes, got " + writes);
                }
                Thread.sleep(5);
            }
        }
    }

    private static QueuedEvent cold(EventEnvelope envelope, QueuedEvent.ClaimLease lease) {
        return new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0, System.nanoTime(), lease);
    }

    private static String at(QueuedEvent.ClaimLease lease) {
        return lease.owner() + "@" + lease.claimedAt();
    }

    private static void awaitNotRunning(InFlightTracker tracker, String eventId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tracker.isRunning(eventId)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("tracker entry never expired: " + eventId);
            }
            Thread.sleep(5);
        }
    }

    /**
     * Writer stamp, then this node's own poller claims it while the hot copy runs. The poller's copy
     * is turned away; its claim is handed to the hot copy, which then settles under it. Fenced on
     * the stamp alone the write would miss, and the row would sit claimed until the lock timeout.
     */
    @Test
    void aHotCopySettlesUnderAClaimHandedOverWhileItRuns() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            running.countDown();
            awaitQuietly(release);
            return DispatchResult.done();
        });
        var store = new FencingStore();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).claimOwner("pod-a").drainTimeoutMs(1000)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(running.await(5, TimeUnit.SECONDS));

            assertTrue(d.enqueueCold(cold(envelope, L1)));
            release.countDown();
            store.awaitWrites(1);
        }

        assertEquals(List.of(envelope.eventId() + ":" + at(L1)), store.writes);
        assertEquals(List.of(), store.releases, "the claim was used, not dropped");
    }

    /**
     * The hot write misses (its stamp was claimed), and the poller's copy arrives after that write
     * but before the hot copy's cleanup. Nothing will write under its claim any more, so instead of
     * being accepted and dropped it is released, and the next poll settles the event.
     */
    @Test
    void aHandOverThatArrivesAfterTheWriteIsReleased() throws Exception {
        AtomicReference<OutboxDispatcher> dispatcher = new AtomicReference<>();
        AtomicReference<Thread> poller = new AtomicReference<>();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        var store = new FencingStore() {
            @Override
            public int markDone(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
                if (claimedAt != null) {
                    return super.markDone(conn, eventId, claimOwner, claimedAt);
                }
                // Hot write: the poller's copy turns up mid-write and must wait for its outcome.
                Thread t = new Thread(() -> dispatcher.get().enqueueCold(cold(envelope, L1)));
                poller.set(t);
                t.start();
                awaitBlockedOrDone(t);
                super.markDone(conn, eventId, claimOwner, claimedAt);
                return 0;
            }
        };
        var registry = new DefaultListenerRegistry().register(ONCE, event -> DispatchResult.done());

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).claimOwner("pod-a").drainTimeoutMs(1000)
                .build()) {
            dispatcher.set(d);
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            store.awaitWrites(1);
            poller.get().join(5000);
            assertEquals(List.of(at(L1)), store.releases, "released exactly, not left to the lock timeout");

            assertTrue(d.enqueueCold(cold(envelope, L2)), "the next poll's claim");
            store.awaitWrites(2);
        }

        assertEquals(List.of(envelope.eventId() + ":pod-a@null", envelope.eventId() + ":" + at(L2)), store.writes);
    }

    /**
     * c1 outlives its tracker entry, c2 runs alongside it, and c3's claim is handed to c2. When c1
     * completes it writes under its own lease and its cleanup leaves c2's hand-over alone.
     */
    @Test
    void aNewerAcquisitionKeepsItsHandOverWhenTheOldOneFinishes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch secondRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            if (calls.incrementAndGet() == 1) {
                firstRunning.countDown();
                awaitQuietly(releaseFirst);
            } else {
                secondRunning.countDown();
                awaitQuietly(releaseSecond);
            }
            return DispatchResult.done();
        });
        var store = new FencingStore();
        var tracker = new DefaultInFlightTracker(200);
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        String id = envelope.eventId();

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(2).claimOwner("pod-a").inFlightTracker(tracker).drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(cold(envelope, L1));
            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));
            awaitNotRunning(tracker, id);

            d.enqueueCold(cold(envelope, L2));
            assertTrue(secondRunning.await(5, TimeUnit.SECONDS));
            d.enqueueCold(cold(envelope, L3));

            releaseFirst.countDown();
            store.awaitWrites(1);
            releaseSecond.countDown();
            store.awaitWrites(2);
        }

        assertEquals(List.of(id + ":" + at(L1), id + ":" + at(L3)), store.writes);
        assertEquals(List.of(), store.releases);
    }

    /**
     * c3's claim reached c1 before c2 was reserved. Reserving c2 retires c1 and moves the claim to
     * c2: c2 settles under it, c1 keeps only its own lease, and c1's cleanup does not release it.
     */
    @Test
    void aHandOverMovesToTheNewerReservation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            if (calls.incrementAndGet() == 1) {
                firstRunning.countDown();
                awaitQuietly(releaseFirst);
            }
            return DispatchResult.done();
        });
        var store = new FencingStore();
        var tracker = new DefaultInFlightTracker(200);
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        String id = envelope.eventId();

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(2).claimOwner("pod-a").inFlightTracker(tracker).drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(cold(envelope, L1));
            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));
            d.enqueueCold(cold(envelope, L3));
            awaitNotRunning(tracker, id);

            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            store.awaitWrites(1);
            releaseFirst.countDown();
            store.awaitWrites(2);
        }

        assertEquals(List.of(id + ":" + at(L3), id + ":" + at(L1)), store.writes);
        assertEquals(List.of(), store.releases);
    }

    /**
     * Duplicates of the claim the running copy already holds — its own lease, and one handed to it —
     * are held, not orphaned. Releasing them would clear the live claim and let another node take
     * the row while the listener still runs.
     */
    @Test
    void aDuplicateOfAHeldClaimIsNotReleased() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            running.countDown();
            awaitQuietly(release);
            return DispatchResult.done();
        });
        var store = new FencingStore();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).claimOwner("pod-a").drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(cold(envelope, L1));
            assertTrue(running.await(5, TimeUnit.SECONDS));

            d.enqueueCold(cold(envelope, new QueuedEvent.ClaimLease("pod-a", L1.claimedAt())));
            d.enqueueCold(cold(envelope, L3));
            d.enqueueCold(cold(envelope, new QueuedEvent.ClaimLease("pod-a", L3.claimedAt())));
            assertEquals(List.of(), store.releases);

            release.countDown();
            store.awaitWrites(1);
        }

        assertEquals(List.of(envelope.eventId() + ":" + at(L3)), store.writes);
        assertEquals(List.of(), store.releases);
    }

    /**
     * Concurrent duplicates of the running copy's claim each make a temporary reservation that the
     * tracker check then undoes. None of those may pass for the current copy refusing: the live
     * claim must survive every one of them, and the running copy settles under it.
     */
    @Test
    void concurrentDuplicatesNeverReleaseTheRunningCopysClaim() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            running.countDown();
            awaitQuietly(release);
            return DispatchResult.done();
        });
        var store = new FencingStore();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        int threads = 8;
        int copiesPerThread = 2000;

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).claimOwner("pod-a").drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(cold(envelope, L1));
            assertTrue(running.await(5, TimeUnit.SECONDS));

            CountDownLatch start = new CountDownLatch(1);
            List<Thread> pollers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    awaitQuietly(start);
                    for (int n = 0; n < copiesPerThread; n++) {
                        d.enqueueCold(cold(envelope, new QueuedEvent.ClaimLease("pod-a", L1.claimedAt())));
                    }
                });
                pollers.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : pollers) {
                t.join(10_000);
            }
            assertEquals(List.of(), store.releases, "a duplicate released the live claim");

            release.countDown();
            store.awaitWrites(1);
        }

        assertEquals(List.of(envelope.eventId() + ":" + at(L1)), store.writes);
    }

    /** A duplicate of a claim that moved to the newer reservation reaches it there, and is not released. */
    @Test
    void aDuplicateOfATransferredClaimIsHeldByTheSuccessor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch secondRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            if (calls.incrementAndGet() == 1) {
                firstRunning.countDown();
                awaitQuietly(releaseFirst);
            } else {
                secondRunning.countDown();
                awaitQuietly(releaseSecond);
            }
            return DispatchResult.done();
        });
        var store = new FencingStore();
        var tracker = new DefaultInFlightTracker(200);
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");
        String id = envelope.eventId();

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(2).claimOwner("pod-a").inFlightTracker(tracker).drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(cold(envelope, L1));
            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));
            d.enqueueCold(cold(envelope, L3));
            awaitNotRunning(tracker, id);
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(secondRunning.await(5, TimeUnit.SECONDS));

            d.enqueueCold(cold(envelope, new QueuedEvent.ClaimLease("pod-a", L3.claimedAt())));
            assertEquals(List.of(), store.releases);

            releaseSecond.countDown();
            store.awaitWrites(1);
            releaseFirst.countDown();
            store.awaitWrites(2);
        }

        assertEquals(List.of(id + ":" + at(L3), id + ":" + at(L1)), store.writes);
        assertEquals(List.of(), store.releases);
    }

    // ── Stores that predate claim fencing ───────────────────────────

    private static class LegacyLockingStore extends StubOutboxStore {
        @Override
        public boolean supportsClaimLocking() {
            return true;
        }
    }

    @Test
    void aClaimOwnerIsRejectedForALockingStoreWithoutFencing() {
        OutboxDispatcher.Builder builder = OutboxDispatcher.builder()
                .connectionProvider(stubCp())
                .outboxStore(new LegacyLockingStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0)
                .claimOwner("pod-a");

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    /** An unleased copy without a claim owner requests no fence, so an existing locking store keeps working. */
    @Test
    void aLockingStoreWithoutFencingStillServesUnfencedWrites() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            delivered.countDown();
            return DispatchResult.done();
        });
        var store = new LegacyLockingStore();

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.COLD, 0));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }

        assertEquals(1, store.markDoneCount.get());
    }

    /**
     * A leased copy names its authority even without a claim owner. A store that cannot honour the
     * fence refuses the write; downgrading it to a plain write could clear a newer holder's claim.
     */
    @Test
    void aLeasedCopyIsNeverWrittenUnfencedOnALockingStoreWithoutFencing() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            delivered.countDown();
            return DispatchResult.done();
        });
        var store = new LegacyLockingStore();

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), LEASE));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }

        assertEquals(0, store.markDoneCount.get(), "no plain write, so no claim was cleared");
    }

    @Test
    void fencedDefaultsDelegateOnlyWhenThereIsNoFenceToLose() {
        var legacy = new LegacyLockingStore();
        assertEquals(1, legacy.markDone(null, "e", null, null), "no owner: unfenced, as before");
        assertThrows(UnsupportedOperationException.class,
                () -> legacy.markDone(null, "e", "pod-a", LEASE.claimedAt()));
        assertThrows(UnsupportedOperationException.class,
                () -> legacy.markRetry(null, "e", Instant.now(), "x", "pod-a", null));
        assertThrows(UnsupportedOperationException.class,
                () -> legacy.markDead(null, "e", "x", "pod-a", null));
        assertThrows(UnsupportedOperationException.class,
                () -> legacy.markDeferred(null, "e", Instant.now(), "pod-a", null));

        var nonLocking = new StubOutboxStore();
        assertEquals(1, nonLocking.markDone(null, "e", "pod-a", LEASE.claimedAt()),
                "a store that never claims has nothing to fence against");
    }

    private static void awaitBlockedOrDone(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.BLOCKED && t.getState() != Thread.State.TERMINATED) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("hand-over thread neither blocked nor finished: " + t.getState());
            }
            Thread.onSpinWait();
        }
    }

    // ── Review follow-ups: tracker capability, metrics isolation, interrupts, plain writes ──

    /** A legal tracker that only does tryAcquire/release: isRunning stays at its default. */
    private static final class AcquireOnlyTracker implements InFlightTracker {
        private final Set<String> held = ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryAcquire(String eventId) {
            return held.add(eventId);
        }

        @Override
        public void release(String eventId) {
            held.remove(eventId);
        }
    }

    @Test
    void claimOwnerNeedsATrackerThatReportsRunningEntries() {
        var e = assertThrows(IllegalArgumentException.class, () -> OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .inFlightTracker(new AcquireOnlyTracker())
                .claimOwner("pod-a")
                .build());
        assertTrue(e.getMessage().contains("supportsIsRunning"), e.getMessage());
    }

    /**
     * Without isRunning a second leased copy would be reserved behind a running one, retire its
     * write authority and drop its own lease. So a leased copy is refused outright — before any
     * reservation, without touching the claim — whether or not claimOwner is set.
     */
    @Test
    void aLeasedCopyIsRefusedByATrackerThatCannotReportRunning() throws Exception {
        var store = new FencingStore();
        CountDownLatch delivered = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            delivered.countDown();
            return DispatchResult.done();
        });
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .inFlightTracker(new AcquireOnlyTracker())
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            var e = assertThrows(IllegalStateException.class, () -> d.enqueueCold(cold(envelope, L1)));
            assertTrue(e.getMessage().contains("supportsIsRunning"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> d.enqueueCold(cold(envelope, L2)));
            assertEquals(List.of(), store.releases, "a refused lease is left alone, not released");

            // Nothing was reserved: an unleased copy of the same event still goes through.
            assertTrue(d.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, 0)));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of(), store.writes, "no fenced write: the unleased copy wrote unfenced");
        assertEquals(1, store.markDoneCount.get());
    }

    @Test
    void aTrackerWithoutIsRunningStillServesUnleasedCopies() throws Exception {
        var store = new StubOutboxStore();
        CountDownLatch delivered = new CountDownLatch(2);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            delivered.countDown();
            return DispatchResult.done();
        });
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .inFlightTracker(new AcquireOnlyTracker())
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            assertTrue(d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0)));
            assertTrue(d.enqueueCold(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.COLD, 0)));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        }
    }

    /** Hot writes are fenced on locked_by = claimOwner; a store stamping someone else makes them all miss. */
    @Test
    void claimOwnerMustMatchTheStoresStamp() {
        var stamping = new StubOutboxStore() {
            @Override
            public Optional<String> writerOwnerId() {
                return Optional.of("pod-a");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(stamping)
                .listenerRegistry(new DefaultListenerRegistry())
                .claimOwner("pod-b")
                .build());
        assertDoesNotThrow(() -> OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(stamping)
                .listenerRegistry(new DefaultListenerRegistry())
                .claimOwner("pod-a").workerCount(0)
                .build().close());
    }

    /** Every exporter call throws; delivery decisions and hand-overs must not notice. */
    private static final MetricsExporter THROWING_METRICS = (MetricsExporter) Proxy.newProxyInstance(
            MetricsExporter.class.getClassLoader(), new Class<?>[]{MetricsExporter.class},
            (proxy, method, args) -> {
                throw new IllegalStateException("metrics backend down: " + method.getName());
            });

    /**
     * A listener that returned Done used to become DEAD (maxAttempts=1) because the duration
     * metric threw inside the listener's try block.
     */
    @Test
    void aThrowingExporterDoesNotTurnASuccessIntoAFailure() throws Exception {
        var store = new StubOutboxStore();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> DispatchResult.done());
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .metrics(THROWING_METRICS).maxAttempts(1)
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            assertTrue(d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0)));
            awaitCount(store.markDoneCount, 1);
        }
        assertEquals(0, store.markDeadCount.get());
        assertEquals(0, store.markRetryCount.get());
    }

    /** The suppression counter used to throw before the hand-over, stranding the poller's claim. */
    @Test
    void aThrowingExporterDoesNotSkipTheHandOver() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            running.countDown();
            awaitQuietly(release);
            return DispatchResult.done();
        });
        var store = new FencingStore();
        EventEnvelope envelope = EventEnvelope.ofJson(ONCE, "{}");

        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .metrics(THROWING_METRICS)
                .workerCount(1).claimOwner("pod-a").drainTimeoutMs(1000)
                .build()) {
            d.enqueueHot(new QueuedEvent(envelope, QueuedEvent.Source.HOT, 0));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertTrue(d.enqueueCold(cold(envelope, L1)));
            release.countDown();
            store.awaitWrites(1);
        }
        assertEquals(List.of(envelope.eventId() + ":" + at(L1)), store.writes);
    }

    @Test
    void theIsolatingExporterOverridesEveryExporterMethod() {
        for (Method m : MetricsExporter.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertDoesNotThrow(() -> IsolatingMetricsExporter.class
                    .getDeclaredMethod(m.getName(), m.getParameterTypes()), "not delegated: " + m);
        }
    }

    /**
     * A listener that re-interrupts its thread and returns Done: the status write must not run on
     * the interrupted thread, or an interrupt-sensitive pool refuses the connection and the
     * outcome is lost.
     */
    @Test
    void aListenersLeftoverInterruptDoesNotLoseTheStatusWrite() throws Exception {
        var store = new StubOutboxStore();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            Thread.currentThread().interrupt();
            return DispatchResult.done();
        });
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(interruptSensitive(new AtomicBoolean())).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            assertTrue(d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0)));
            awaitCount(store.markDoneCount, 1);
        }
    }

    /** Refuses a connection to an interrupted thread, like HikariCP's borrow. */
    private static ConnectionProvider interruptSensitive(AtomicBoolean refused) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) {
                refused.set(true);
                throw new SQLException("Interrupted during connection acquisition");
            }
            return stubCp().getConnection();
        };
    }

    @Test
    void aThrowingListenersLeftoverInterruptDoesNotLoseTheRetry() throws Exception {
        var store = new StubOutboxStore();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("boom");
        });
        AtomicBoolean refused = new AtomicBoolean();
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(interruptSensitive(refused)).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            assertTrue(d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0)));
            awaitCount(store.markRetryCount, 1);
        }
        assertFalse(refused.get());
    }

    /**
     * close() sets running=false before it starts draining, so "not running" does not mean
     * "forced". A listener's own leftover interrupt during a graceful drain is still a leftover:
     * its status write must land.
     */
    @Test
    void aLeftoverInterruptDuringAGracefulDrainDoesNotLoseTheStatusWrite() throws Exception {
        var store = new StubOutboxStore();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            started.countDown();
            awaitQuietly(proceed);
            Thread.currentThread().interrupt();
            return DispatchResult.done();
        });
        AtomicBoolean refused = new AtomicBoolean();
        var d = OutboxDispatcher.builder()
                .connectionProvider(interruptSensitive(refused)).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(5000)
                .build();
        assertTrue(d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0)));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        Thread closer = new Thread(d::close);
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(TAIL, "{}"), QueuedEvent.Source.HOT, 0))) {
            assertTrue(System.nanoTime() < deadline, "close() never began");
            Thread.sleep(5);
        }
        Thread.sleep(50); // running=false is set right after accepting=false
        proceed.countDown();
        closer.join(10_000);

        assertEquals(1, store.markDoneCount.get(), "a graceful drain keeps the leftover-interrupt fix");
        assertFalse(refused.get());
    }

    /**
     * Forced shutdown interrupts a listener; it re-interrupts and returns. The status write must
     * still see that interrupt, so a connection acquisition blocked on an exhausted pool is
     * cancelled — clearing it there kept the worker alive past close().
     */
    @Test
    void aForcedCloseStillCancelsAStatusWriteBlockedOnThePool() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch pool = new CountDownLatch(1);
        AtomicBoolean interruptedAtAcquire = new AtomicBoolean();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });
        ConnectionProvider exhausted = () -> {
            if (Thread.currentThread().isInterrupted()) {
                interruptedAtAcquire.set(true);
                throw new SQLException("Interrupted during connection acquisition");
            }
            try {
                if (!pool.await(10, TimeUnit.SECONDS)) {
                    throw new SQLException("pool exhausted");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted during connection acquisition", e);
            }
            return stubCp().getConnection();
        };
        var d = OutboxDispatcher.builder()
                .connectionProvider(exhausted).outboxStore(new StubOutboxStore()).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(100)
                .build();
        try {
            assertTrue(d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson(ONCE, "{}"), QueuedEvent.Source.HOT, 0)));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            long startNs = System.nanoTime();
            d.close();
            long closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            assertTrue(interruptedAtAcquire.get(), "the forced-shutdown interrupt must reach the acquisition");
            assertTrue(closeMs < 3000, "close() waited " + closeMs + "ms for a worker stuck on the pool");
        } finally {
            pool.countDown();
        }
    }

    /**
     * An unfenced write goes through the plain overload, so a store that customised the plain
     * write (an audit column, a dialect tweak) keeps it. A fenced write still uses the fenced one.
     */
    @Test
    void unfencedWritesUseThePlainOverload() throws Exception {
        var store = new FencingStore();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> DispatchResult.done());
        EventEnvelope hot = EventEnvelope.ofJson(ONCE, "{}");
        EventEnvelope claimed = EventEnvelope.ofJson(ONCE, "{}");
        try (var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(1).drainTimeoutMs(1000)
                .build()) {
            d.enqueueHot(new QueuedEvent(hot, QueuedEvent.Source.HOT, 0));
            awaitCount(store.markDoneCount, 1);
            d.enqueueCold(cold(claimed, L1));
            store.awaitWrites(1);
        }
        assertEquals(1, store.markDoneCount.get(), "the unleased hot copy used the plain markDone");
        assertEquals(List.of(claimed.eventId() + ":" + at(L1)), store.writes, "the leased copy stayed fenced");
    }

    // ── Shutdown: hand back what dropped copies held ──────────────────

    /**
     * An exclusive-claim store stamping for pod-a. Records each release as "eventId:owner@claimedAt"
     * and each stamp release as "eventId:stamp@owner"; every fenced write lands.
     */
    private static class ExclusiveStore extends FencingStore {
        final List<String> released = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsClaimLocking() {
            return true;
        }

        @Override
        public boolean supportsClaimFencing() {
            return true;
        }

        @Override
        public Optional<String> writerOwnerId() {
            return Optional.of("pod-a");
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error,
                             String claimOwner, Instant claimedAt) {
            writes.add(eventId + ":" + claimOwner + "@" + claimedAt);
            return 1;
        }

        @Override
        public int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
            released.add(eventId + ":" + claimOwner + "@" + claimedAt);
            return 1;
        }

        @Override
        public int releaseStamp(Connection conn, String eventId, String writerOwner) {
            released.add(eventId + ":stamp@" + writerOwner);
            return 1;
        }
    }

    private static OutboxDispatcher exclusive(FencingStore store, DefaultListenerRegistry registry,
                                              int workers, long drainMs) {
        return OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(registry)
                .workerCount(workers).claimOwner("pod-a").drainTimeoutMs(drainMs)
                .build();
    }

    /**
     * Copies still queued when the workers stop never run, so their rows are handed back at once
     * instead of waiting for the lock timeout: a cold copy's lease, a claim handed over to a copy,
     * and a hot copy's writer stamp.
     */
    @Test
    void closeReleasesWhatQueuedCopiesHeld() {
        var store = new ExclusiveStore();
        EventEnvelope hot = EventEnvelope.ofJson(ONCE, "{}");
        EventEnvelope claimed = EventEnvelope.ofJson(ONCE, "{}");
        var d = exclusive(store, new DefaultListenerRegistry(), 0, 1000);
        assertTrue(d.enqueueHot(new QueuedEvent(hot, QueuedEvent.Source.HOT, 0)));
        assertTrue(d.enqueueCold(cold(hot, L2)), "turned away: its claim is handed to the hot copy");
        assertTrue(d.enqueueCold(cold(claimed, L1)));
        assertEquals(List.of(), store.released);

        d.close();

        assertEquals(List.of(
                hot.eventId() + ":" + at(L2),
                hot.eventId() + ":stamp@pod-a",
                claimed.eventId() + ":" + at(L1)), store.released);
    }

    /** A non-exclusive claim cannot prove no other copy holds the same lease, so nothing is released. */
    @Test
    void aNonExclusiveStoreKeepsItsClaimsAtShutdown() {
        var store = new FencingStore();
        var d = exclusive(store, new DefaultListenerRegistry(), 0, 1000);
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L1)));

        d.close();

        assertEquals(List.of(), store.releases);
    }

    /**
     * The worker of a copy it already dequeued settles that copy itself; only what is still queued
     * is released, and only after the forced shutdown has stopped every worker.
     */
    @Test
    void aForcedShutdownReleasesOnlyWhatIsStillQueued() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            running.countDown();
            awaitQuietly(new CountDownLatch(1)); // until shutdownNow interrupts it
            return DispatchResult.done();
        });
        var store = new ExclusiveStore();
        EventEnvelope first = EventEnvelope.ofJson(ONCE, "{}");
        EventEnvelope queued = EventEnvelope.ofJson(ONCE, "{}");
        var d = exclusive(store, registry, 1, 100);
        assertTrue(d.enqueueCold(cold(first, L1)));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(d.enqueueCold(cold(queued, L2)));

        d.close();

        assertEquals(List.of(first.eventId() + ":" + at(L1)), store.writes, "the running copy wrote");
        assertEquals(List.of(queued.eventId() + ":" + at(L2)), store.released);
    }

    /**
     * An interrupted close keeps its cancellation: no database round trips, every claim left to
     * expire. No workers here, so the pool terminates at once and only the interrupt stops the release.
     */
    @Test
    void anInterruptedCloseReleasesNothing() throws Exception {
        CountDownLatch connOpened = new CountDownLatch(1);
        var store = new ExclusiveStore();
        var d = OutboxDispatcher.builder()
                .connectionProvider(() -> {
                    connOpened.countDown();
                    return stubCp().getConnection();
                })
                .outboxStore(store).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).claimOwner("pod-a")
                .build();
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L1)));

        Thread.currentThread().interrupt();
        d.close();
        boolean interrupted = Thread.interrupted();

        assertTrue(interrupted, "close keeps the caller's interrupt");
        assertFalse(connOpened.await(500, TimeUnit.MILLISECONDS), "no connection is even requested");
        assertEquals(List.of(), store.released);
    }

    /**
     * A worker that ignores the shutdown interrupt may still write under a dropped copy's claim, so
     * without confirmed termination nothing is released. Waits out close()'s 5-second grace.
     */
    @Test
    void workersThatDoNotStopKeepEveryClaim() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        var registry = new DefaultListenerRegistry().register(ONCE, event -> {
            running.countDown();
            while (!stop.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // deliberately deaf to shutdownNow
                }
            }
            return DispatchResult.done();
        });
        var store = new ExclusiveStore();
        var d = exclusive(store, registry, 1, 50);
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L1)));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L2)));

        d.close();
        stop.set(true);

        assertEquals(List.of(), store.released);
    }

    /**
     * Documented limit: an enqueue that passed the accepting check before close can publish after the
     * queues were drained. Its copy never runs and is not released; the row waits for the lock timeout.
     */
    @Test
    void aCopyPublishedAfterTheDrainIsLeftToExpire() throws Exception {
        CountDownLatch inReserve = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        InFlightTracker pausing = new InFlightTracker() {
            @Override
            public boolean tryAcquire(String eventId) {
                return true;
            }

            @Override
            public void release(String eventId) {
            }

            @Override
            public boolean supportsIsRunning() {
                return true;
            }

            @Override
            public boolean isRunning(String eventId) {
                inReserve.countDown();
                awaitQuietly(resume);
                return false;
            }
        };
        var store = new ExclusiveStore();
        var d = OutboxDispatcher.builder()
                .connectionProvider(stubCp()).outboxStore(store).listenerRegistry(new DefaultListenerRegistry())
                .inFlightTracker(pausing).workerCount(0).claimOwner("pod-a")
                .build();
        AtomicBoolean accepted = new AtomicBoolean();
        Thread producer = new Thread(() -> accepted.set(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L1))));
        producer.start();
        assertTrue(inReserve.await(5, TimeUnit.SECONDS));

        d.close();
        resume.countDown();
        producer.join(5000);

        assertTrue(accepted.get(), "published after close");
        assertEquals(List.of(), store.released);
    }

    /** One failed release does not stop the rest. */
    @Test
    void aFailedReleaseAtShutdownDoesNotStopTheRest() {
        EventEnvelope failing = EventEnvelope.ofJson(ONCE, "{}");
        EventEnvelope next = EventEnvelope.ofJson(ONCE, "{}");
        var store = new ExclusiveStore() {
            @Override
            public int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
                if (eventId.equals(failing.eventId())) {
                    throw new IllegalStateException("connection reset");
                }
                return super.releaseClaim(conn, eventId, claimOwner, claimedAt);
            }
        };
        var d = exclusive(store, new DefaultListenerRegistry(), 0, 1000);
        assertTrue(d.enqueueCold(cold(failing, L1)));
        assertTrue(d.enqueueCold(cold(next, L2)));

        d.close();

        assertEquals(List.of(next.eventId() + ":" + at(L2)), store.released);
    }

    /** No connection for the cleanup: close still completes, and the claims expire as before. */
    @Test
    void noConnectionAtShutdownLeavesTheClaimsToExpire() {
        var store = new ExclusiveStore();
        var d = OutboxDispatcher.builder()
                .connectionProvider(() -> {
                    throw new SQLException("pool closed");
                })
                .outboxStore(store).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).claimOwner("pod-a")
                .build();
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L1)));

        assertDoesNotThrow(d::close);
        assertEquals(List.of(), store.released);
    }

    /** A pool that cannot hand out a connection does not hold up close(); the late one is only closed. */
    @Test
    void aStalledConnectionDoesNotHoldUpClose() throws Exception {
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch connClosed = new CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        ConnectionProvider pool = recordingCp(calls, connClosed);
        var store = new ExclusiveStore();
        var d = OutboxDispatcher.builder()
                .connectionProvider(() -> {
                    awaitDeaf(unblock);
                    return pool.getConnection();
                })
                .outboxStore(store).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).claimOwner("pod-a")
                .build();
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L1)));

        long start = System.nanoTime();
        d.close();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        unblock.countDown();

        assertTrue(tookMs < OutboxDispatcher.RELEASE_ON_CLOSE_BUDGET_MS + 500, "took " + tookMs + "ms");
        assertTrue(connClosed.await(5, TimeUnit.SECONDS), "the late connection is closed");
        assertEquals(List.of("close"), calls);
        assertEquals(List.of(), store.released);
    }

    /**
     * A statement that stalls past the budget does not hold up close(); once it returns, the release
     * stops — neither the same copy's stamp nor the next row is touched.
     */
    @Test
    void aStalledReleaseDoesNotHoldUpCloseAndStopsAfterIt() throws Exception {
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch connClosed = new CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        var store = new ExclusiveStore() {
            @Override
            public int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
                awaitDeaf(unblock);
                return super.releaseClaim(conn, eventId, claimOwner, claimedAt);
            }
        };
        EventEnvelope hot = EventEnvelope.ofJson(ONCE, "{}");
        EventEnvelope next = EventEnvelope.ofJson(ONCE, "{}");
        var d = OutboxDispatcher.builder()
                .connectionProvider(recordingCp(calls, connClosed))
                .outboxStore(store).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).claimOwner("pod-a")
                .build();
        assertTrue(d.enqueueHot(new QueuedEvent(hot, QueuedEvent.Source.HOT, 0)));
        assertTrue(d.enqueueCold(cold(hot, L2)), "turned away: its claim is handed to the hot copy");
        assertTrue(d.enqueueCold(cold(next, L1)));

        long start = System.nanoTime();
        d.close();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        unblock.countDown();

        assertTrue(tookMs < OutboxDispatcher.RELEASE_ON_CLOSE_BUDGET_MS + 500, "took " + tookMs + "ms");
        assertTrue(connClosed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(hot.eventId() + ":" + at(L2)), store.released);
        assertTrue(calls.contains("setNetworkTimeout"), "the driver bounds a statement still running: " + calls);
    }

    /** An interrupt during the release returns close() at once and stops the release after its statement. */
    @Test
    void anInterruptDuringTheReleaseStopsIt() throws Exception {
        CountDownLatch inRelease = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch connClosed = new CountDownLatch(1);
        var store = new ExclusiveStore() {
            @Override
            public int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
                inRelease.countDown();
                awaitDeaf(unblock);
                return super.releaseClaim(conn, eventId, claimOwner, claimedAt);
            }
        };
        EventEnvelope first = EventEnvelope.ofJson(ONCE, "{}");
        var d = OutboxDispatcher.builder()
                .connectionProvider(recordingCp(new CopyOnWriteArrayList<>(), connClosed))
                .outboxStore(store).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).claimOwner("pod-a")
                .build();
        assertTrue(d.enqueueCold(cold(first, L1)));
        assertTrue(d.enqueueCold(cold(EventEnvelope.ofJson(ONCE, "{}"), L2)));
        AtomicBoolean keptInterrupt = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            d.close();
            keptInterrupt.set(Thread.currentThread().isInterrupted());
        });

        closer.start();
        assertTrue(inRelease.await(5, TimeUnit.SECONDS));
        closer.interrupt();
        closer.join(OutboxDispatcher.RELEASE_ON_CLOSE_BUDGET_MS / 2);
        boolean returnedAtOnce = !closer.isAlive();
        unblock.countDown();

        assertTrue(returnedAtOnce, "close returns on the interrupt, not at the budget");
        assertTrue(keptInterrupt.get(), "close keeps the caller's interrupt");
        assertTrue(connClosed.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(first.eventId() + ":" + at(L1)), store.released);
    }

    /** A connection that records each call by name and counts down when closed. */
    private static ConnectionProvider recordingCp(List<String> calls, CountDownLatch closed) {
        return () -> (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if ("close".equals(method.getName())) {
                        closed.countDown();
                    }
                    return null;
                });
    }

    /**
     * Waits (up to 5s) like a driver or pool call that swallows interrupts: the interrupt is consumed and
     * not restored, so only the dispatcher's own cancellation can stop what follows.
     */
    private static void awaitDeaf(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (latch.getCount() > 0 && System.nanoTime() - deadline < 0) {
            try {
                latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException swallowed) {
                // deliberately not restored
            }
        }
    }

    private static void awaitCount(AtomicInteger counter, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counter.get() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + expected + ", got " + counter.get());
            }
            Thread.sleep(5);
        }
    }
}
