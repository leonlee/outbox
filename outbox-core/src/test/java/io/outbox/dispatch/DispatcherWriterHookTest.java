package io.outbox.dispatch;

import io.outbox.EventEnvelope;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
