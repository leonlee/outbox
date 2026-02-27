package outbox.spi;

/**
 * Observability hook for exporting outbox counters and gauges to a metrics backend.
 *
 * <p>The {@link #NOOP} instance discards all metrics silently. Implement this interface
 * to bridge into Micrometer, Prometheus, or other monitoring systems.
 */
public interface MetricsExporter {

    /**
     * No-op instance that discards all metrics.
     */
    MetricsExporter NOOP = new Noop();

    /**
     * Increments the count of events successfully enqueued via the hot path.
     */
    void incrementHotEnqueued();

    /**
     * Increments the count of events dropped because the hot queue was full.
     */
    void incrementHotDropped();

    /**
     * Increments the count of events enqueued via the cold (poller) path.
     */
    void incrementColdEnqueued();

    /**
     * Increments the count of events dispatched successfully.
     */
    void incrementDispatchSuccess();

    /**
     * Increments the count of events that failed and will be retried.
     */
    void incrementDispatchFailure();

    /**
     * Increments the count of events moved to DEAD (no more retries).
     */
    void incrementDispatchDead();

    /**
     * Increments the count of events deferred by a handler returning
     * {@link outbox.DispatchResult.RetryAfter}.
     */
    default void incrementDispatchDeferred() {
    }

    /**
     * Increments the count of delayed events skipped on the hot path.
     * These events will be delivered by the poller when their {@code availableAt} time arrives.
     */
    default void incrementHotSkippedDelayed() {
    }

    /**
     * Records the current depth of both dispatch queues.
     *
     * @param hotDepth  number of events in the hot queue
     * @param coldDepth number of events in the cold queue
     */
    void recordQueueDepths(int hotDepth, int coldDepth);

    /**
     * Records the lag (in milliseconds) of the oldest pending event.
     *
     * @param lagMs lag in milliseconds (always non-negative)
     */
    void recordOldestLagMs(long lagMs);

    /**
     * Records end-to-end dispatch latency: from {@code occurredAt} to dispatch completion.
     *
     * @param latencyMs latency in milliseconds (always non-negative)
     */
    default void recordDispatchLatencyMs(long latencyMs) {
    }

    /**
     * Records the time spent executing the event listener only.
     *
     * @param durationMs listener execution time in milliseconds (always non-negative)
     */
    default void recordListenerDurationMs(long durationMs) {
    }

    /**
     * Default no-op implementation that discards all metrics.
     */
    final class Noop implements MetricsExporter {
        @Override
        public void incrementHotEnqueued() {
        }

        @Override
        public void incrementHotDropped() {
        }

        @Override
        public void incrementColdEnqueued() {
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
        public void incrementDispatchDeferred() {
        }

        @Override
        public void recordQueueDepths(int hotDepth, int coldDepth) {
        }

        @Override
        public void recordOldestLagMs(long lagMs) {
        }
    }
}
