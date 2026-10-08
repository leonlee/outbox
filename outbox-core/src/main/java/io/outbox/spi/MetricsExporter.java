package io.outbox.spi;

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
     * Increments the count of events the hot path accepted from the writer hook.
     *
     * <p>Accepted, not necessarily queued: a copy of an event that is already queued or running is
     * refused-but-accepted (the event is on its way, so falling back to the poller would double
     * it), and lands in {@link #incrementDispatchSuppressed()} instead of the queue. Same shape as
     * {@link #incrementColdEnqueued()} on the poller side; the queue depth gauges are the accurate
     * view of what is actually queued.
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
     * {@link io.outbox.DispatchResult.RetryAfter}.
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
     * Increments the count of events skipped on the hot path because they were already older than
     * {@link OutboxStore#WRITER_STAMP_MAX_AGE} at commit — backfills, in practice. The store
     * inserts those rows unowned, so hot delivery would race every node's claim; the poller
     * delivers them instead. Not a failure, and not the same thing as a <em>delayed</em> event,
     * which was skipped because its {@code availableAt} lies in the future.
     */
    default void incrementHotSkippedStale() {
    }

    /**
     * Increments the count of events refused by the hot path because its head-of-line age exceeded
     * the trip threshold.
     *
     * <p><b>This is a SUBSET of {@link #incrementHotDropped()}, not a sibling of it.</b> Every
     * refusal — full queue or tripped breaker — falls back to the poller and is counted as dropped
     * by {@code DispatcherWriterHook}, because a boolean return cannot carry the reason. So:
     * {@code dropped} = all fallbacks, {@code tripped} = the slow-path subset, and
     * {@code dropped - tripped} = the full-queue ones. Charting them as two independent series
     * double-counts every trip.
     */
    default void incrementHotTripped() {
    }

    /**
     * Increments the count of duplicates refused by the {@code InFlightTracker} — the event was
     * already in flight, or (with replay suppression on) had already settled. This is the copy that
     * was NOT delivered, so it is the direct measure of the guard working.
     *
     * <p>Counted wherever a duplicate is refused: at the hot queue, at the cold queue, and at
     * dispatch when a worker loses the acquire race. It is one aggregate across all three, so no
     * arithmetic against {@link #incrementColdEnqueued()} — which counts rows the poller handed
     * over, queued or not — recovers the exact queue inflow. The queue depth gauges are the
     * accurate view of what is actually queued.
     */
    default void incrementDispatchSuppressed() {
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
