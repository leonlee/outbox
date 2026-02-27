package outbox.dispatch;

import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.DispatchResult;
import outbox.RetryAfterException;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.ConnectionProvider;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxDispatcherTest {

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
    void builderRejectsMaxAttemptsLessThanOne() {
        assertThrows(IllegalArgumentException.class, () ->
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
    void unroutableEventIsMarkedDead() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var store = new StubOutboxStore() {
            @Override
            public int markDead(Connection conn, String eventId, String error) {
                super.markDead(conn, eventId, error);
                latch.countDown();
                return 1;
            }
        };

        // Empty registry — no listeners
        var registry = new DefaultListenerRegistry();

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            EventEnvelope event = EventEnvelope.ofJson("UnknownEvent", "{}");
            d.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
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
                .register("Test", event -> {
                });

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
        registry.register("DoneResult", new outbox.EventListener() {
            @Override
            public void onEvent(EventEnvelope event) {
            }

            @Override
            public DispatchResult handleEvent(EventEnvelope event) {
                return DispatchResult.done();
            }
        });

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
        registry.register("DeferResult", new outbox.EventListener() {
            @Override
            public void onEvent(EventEnvelope event) {
            }

            @Override
            public DispatchResult handleEvent(EventEnvelope event) {
                return DispatchResult.retryAfter(Duration.ofSeconds(30));
            }
        });

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
    void handlerReturningNullTreatedAsDone() throws Exception {
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
        registry.register("NullResult", new outbox.EventListener() {
            @Override
            public void onEvent(EventEnvelope event) {
            }

            @Override
            public DispatchResult handleEvent(EventEnvelope event) {
                return null;
            }
        });

        try (var d = newDispatcher(1, 10, 10, registry, store)) {
            d.enqueueHot(new QueuedEvent(EventEnvelope.ofJson("NullResult", "{}"), QueuedEvent.Source.HOT, 0));

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, store.markDoneCount.get());
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
        outbox.spi.OutboxStore storeWithDefault = new outbox.spi.OutboxStore() {
            @Override
            public void insertNew(Connection conn, outbox.EventEnvelope event) {
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
            public java.util.List<outbox.model.OutboxEvent> pollPending(
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
    void lambdaListenerBackwardCompat() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        var registry = new DefaultListenerRegistry()
                .register("LambdaCompat", event -> {
                    received.set(event.payloadJson());
                    latch.countDown();
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

    private static ConnectionProvider stubCp() {
        return () -> (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    if ("setAutoCommit".equals(method.getName())) return null;
                    return null;
                });
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
}
