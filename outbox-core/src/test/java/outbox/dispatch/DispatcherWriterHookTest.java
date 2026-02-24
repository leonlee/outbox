package outbox.dispatch;

import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.MetricsExporter;

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
