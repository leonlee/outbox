package io.outbox.dispatch;

import io.outbox.EventEnvelope;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.outbox.dispatch.DispatcherWriterHook.REFUSAL_WARNING_INTERVAL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherWriterHookTest {

    @Test
    void constructorRejectsNullDispatcher() {
        assertThrows(NullPointerException.class, () -> new DispatcherWriterHook(null));
    }

    @Test
    void afterCommitEnqueuesEachEventToHotQueue() {
        var dispatcher = newDispatcher(0, 10);
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics);

        List<EventEnvelope> events = List.of(
                EventEnvelope.ofJson("A", "{}"),
                EventEnvelope.ofJson("B", "{}")
        );
        hook.afterCommit(events);

        // workerCount=0 means events stay in queue
        assertEquals(2, metrics.hotEnqueued.get());
        assertEquals(0, metrics.hotDropped.get());
    }

    @Test
    void afterCommitIncrementHotEnqueuedMetrics() {
        var dispatcher = newDispatcher(0, 10);
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics);

        hook.afterCommit(List.of(EventEnvelope.ofJson("A", "{}")));

        assertEquals(1, metrics.hotEnqueued.get());
        assertEquals(0, metrics.hotDropped.get());
    }

    @Test
    void afterCommitIncrementHotDroppedWhenQueueFull() {
        var dispatcher = newDispatcher(0, 1); // capacity=1
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics);

        hook.afterCommit(List.of(
                EventEnvelope.ofJson("A", "{}"),
                EventEnvelope.ofJson("B", "{}"),
                EventEnvelope.ofJson("C", "{}")
        ));

        assertEquals(1, metrics.hotEnqueued.get());
        assertEquals(2, metrics.hotDropped.get());
    }

    @Test
    void afterCommitSkipsDelayedEvents() {
        var dispatcher = newDispatcher(0, 10);
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics);

        EventEnvelope delayed = EventEnvelope.builder("Delayed")
                .deliverAfter(Duration.ofHours(1))
                .payloadJson("{}")
                .build();
        EventEnvelope immediate = EventEnvelope.ofJson("Immediate", "{}");

        hook.afterCommit(List.of(delayed, immediate));

        assertEquals(1, metrics.hotEnqueued.get());
        assertEquals(0, metrics.hotDropped.get());
        assertEquals(1, metrics.hotSkippedDelayed.get());
    }

    @Test
    void afterCommitSkipsAllDelayedEvents() {
        var dispatcher = newDispatcher(0, 10);
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics);

        EventEnvelope delayed1 = EventEnvelope.builder("D1")
                .availableAt(Instant.now().plusSeconds(3600))
                .payloadJson("{}")
                .build();
        EventEnvelope delayed2 = EventEnvelope.builder("D2")
                .deliverAfter(Duration.ofMinutes(30))
                .payloadJson("{}")
                .build();

        hook.afterCommit(List.of(delayed1, delayed2));

        assertEquals(0, metrics.hotEnqueued.get());
        assertEquals(0, metrics.hotDropped.get());
        assertEquals(2, metrics.hotSkippedDelayed.get());
    }

    /**
     * An event too stale for a writer stamp must not travel the hot path either.
     *
     * <p>The store inserts events older than {@link OutboxStore#WRITER_STAMP_MAX_AGE} unowned —
     * a stamp that old reads as an expired lease to every poller. Unowned means any node can claim
     * the row immediately, so hot-delivering it on this node races that claim and recreates the
     * cross-node backfill duplicate the stamp exists to prevent. Poller-only for those.
     */
    @Test
    void afterCommitSkipsEventsTooStaleToCarryAWriterStamp() {
        var dispatcher = newDispatcher(0, 10);
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics);

        EventEnvelope stale = EventEnvelope.builder("Backfill")
                .occurredAt(Instant.now().minus(OutboxStore.WRITER_STAMP_MAX_AGE).minusSeconds(1))
                .payloadJson("{}")
                .build();
        EventEnvelope fresh = EventEnvelope.ofJson("Live", "{}");

        hook.afterCommit(List.of(stale, fresh));

        assertEquals(1, metrics.hotEnqueued.get(), "the fresh event still rides the hot path");
        assertEquals(1, metrics.hotSkippedStale.get(), "the stale one is left to the poller");
        assertEquals(0, metrics.hotSkippedDelayed.get(),
                "a backfill is not a delayed event and must not read as a scheduling problem");
        assertEquals(0, metrics.hotDropped.get());
    }

    @Test
    void afterCommitWithNullMetricsFallsBackToNoop() {
        var dispatcher = newDispatcher(0, 10);
        var hook = new DispatcherWriterHook(dispatcher, null);

        assertDoesNotThrow(() ->
                hook.afterCommit(List.of(EventEnvelope.ofJson("A", "{}"))));
    }

    /**
     * A saturated hot queue refuses every commit, so the warning is limited to one per interval and
     * names the refusals it stood for; the metric still counts each one.
     */
    @Test
    void refusalWarningsAreLimitedToOnePerInterval() {
        // Near the top of the range: nanoTime may wrap, so deadlines must compare by difference.
        var clock = new AtomicLong(Long.MAX_VALUE - 5);
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(newDispatcher(0, 1), metrics, () -> true, clock::get);
        EventEnvelope firstRefused = EventEnvelope.ofJson("B", "{}");
        EventEnvelope afterInterval = EventEnvelope.ofJson("E", "{}");

        List<LogRecord> warnings = captureWarnings(() -> {
            hook.afterCommit(List.of(EventEnvelope.ofJson("A", "{}"), firstRefused, EventEnvelope.ofJson("C", "{}")));
            clock.addAndGet(REFUSAL_WARNING_INTERVAL.toNanos() - 1);
            hook.afterCommit(List.of(EventEnvelope.ofJson("D", "{}")));
            clock.addAndGet(1);
            hook.afterCommit(List.of(afterInterval));
            hook.afterCommit(List.of(EventEnvelope.ofJson("F", "{}")));
            clock.addAndGet(REFUSAL_WARNING_INTERVAL.toNanos());
            hook.afterCommit(List.of(EventEnvelope.ofJson("G", "{}")));
        });

        assertEquals(6, metrics.hotDropped.get(), "every refusal still counts");
        assertEquals(3, warnings.size(), "first refusal, then one per interval: " + warnings);
        String first = warnings.get(0).getMessage();
        assertTrue(first.contains(firstRefused.eventId()), first);
        assertFalse(first.contains("more fell back"), first);
        String next = warnings.get(1).getMessage();
        assertTrue(next.contains(afterInterval.eventId()), next);
        assertTrue(next.contains("; 2 more fell back since the last warning"), next);
        String third = warnings.get(2).getMessage();
        assertTrue(third.contains("; 1 more fell back"), "counts only since the last warning: " + third);
    }

    @Test
    void concurrentRefusalsWarnOnceAndCountEveryOneSuppressed() throws Exception {
        int threads = 8;
        int perThread = 50;
        var clock = new AtomicLong();
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(newDispatcher(0, 1), metrics, () -> true, clock::get);
        hook.afterCommit(List.of(EventEnvelope.ofJson("Fill", "{}")));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<LogRecord> warnings = captureWarnings(() -> {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        hook.afterCommit(List.of(EventEnvelope.ofJson("X", "{}")));
                    }
                    return null;
                }));
            }
            start.countDown();
            try {
                for (Future<?> f : done) {
                    f.get(10, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            clock.addAndGet(REFUSAL_WARNING_INTERVAL.toNanos());
            hook.afterCommit(List.of(EventEnvelope.ofJson("Y", "{}")));
        });
        pool.shutdownNow();

        assertEquals(threads * perThread + 1, metrics.hotDropped.get());
        assertEquals(2, warnings.size(), warnings.toString());
        String next = warnings.get(1).getMessage();
        assertTrue(next.contains("; " + (threads * perThread - 1) + " more fell back"), next);
    }

    /** An enqueue that throws is a different fault from a full queue: each one keeps its stack trace. */
    @Test
    void enqueueFailuresAreNotLimited() {
        InFlightTracker broken = new InFlightTracker() {
            @Override
            public boolean tryAcquire(String eventId) {
                return true;
            }

            @Override
            public void release(String eventId) {
            }

            @Override
            public boolean isRunning(String eventId) {
                throw new IllegalStateException("tracker down");
            }
        };
        OutboxDispatcher dispatcher = OutboxDispatcher.builder()
                .connectionProvider(() -> {
                    throw new UnsupportedOperationException();
                })
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .inFlightTracker(broken)
                .workerCount(0)
                .build();
        var metrics = new CountingMetrics();
        var hook = new DispatcherWriterHook(dispatcher, metrics, () -> true, () -> 0L);

        List<LogRecord> warnings = captureWarnings(() ->
                hook.afterCommit(List.of(EventEnvelope.ofJson("A", "{}"), EventEnvelope.ofJson("B", "{}"))));

        assertEquals(2, metrics.hotDropped.get());
        assertEquals(2, warnings.size(), warnings.toString());
        for (LogRecord warning : warnings) {
            assertNotNull(warning.getThrown(), warning.getMessage());
        }
    }

    private static List<LogRecord> captureWarnings(Runnable action) {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(DispatcherWriterHook.class.getName());
        logger.addHandler(capture);
        try {
            action.run();
        } finally {
            logger.removeHandler(capture);
        }
        return records;
    }

    private static OutboxDispatcher newDispatcher(int workerCount, int hotCapacity) {
        return OutboxDispatcher.builder()
                .connectionProvider(() -> {
                    throw new UnsupportedOperationException();
                })
                .outboxStore(new StubOutboxStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .workerCount(workerCount)
                .hotQueueCapacity(hotCapacity)
                .coldQueueCapacity(10)
                .build();
    }

    static final class CountingMetrics implements MetricsExporter {
        final AtomicInteger hotEnqueued = new AtomicInteger();
        final AtomicInteger hotDropped = new AtomicInteger();
        final AtomicInteger hotSkippedDelayed = new AtomicInteger();
        final AtomicInteger hotSkippedStale = new AtomicInteger();
        final AtomicInteger coldEnqueued = new AtomicInteger();

        @Override
        public void incrementHotEnqueued() {
            hotEnqueued.incrementAndGet();
        }

        @Override
        public void incrementHotDropped() {
            hotDropped.incrementAndGet();
        }

        @Override
        public void incrementHotSkippedDelayed() {
            hotSkippedDelayed.incrementAndGet();
        }

        @Override
        public void incrementHotSkippedStale() {
            hotSkippedStale.incrementAndGet();
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
