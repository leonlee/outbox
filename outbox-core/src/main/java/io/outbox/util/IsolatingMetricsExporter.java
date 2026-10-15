package io.outbox.util;

import io.outbox.spi.MetricsExporter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a {@link MetricsExporter} so that an exception from it never reaches the caller.
 *
 * <p>Metrics calls sit between the steps that decide an event's fate — a lease hand-over, a status
 * write, the listener's own try block. An exporter that throws there could strand a claim or turn a
 * successful delivery into RETRY or DEAD. Wrapped, a failure is logged (WARNING the first time,
 * FINE after, so a broken backend does not flood the log once per event) and delivery carries on.
 */
public final class IsolatingMetricsExporter implements MetricsExporter {
    private static final Logger logger = Logger.getLogger(IsolatingMetricsExporter.class.getName());

    private final MetricsExporter delegate;
    private final AtomicBoolean warned = new AtomicBoolean();

    private IsolatingMetricsExporter(MetricsExporter delegate) {
        this.delegate = delegate;
    }

    /**
     * Wraps {@code exporter}, or returns {@link MetricsExporter#NOOP} for {@code null}.
     *
     * @param exporter the exporter to isolate, may be {@code null}
     * @return an exporter whose methods never throw
     */
    public static MetricsExporter of(MetricsExporter exporter) {
        if (exporter == null || exporter == MetricsExporter.NOOP) {
            return MetricsExporter.NOOP;
        }
        if (exporter instanceof IsolatingMetricsExporter) {
            return exporter;
        }
        return new IsolatingMetricsExporter(Objects.requireNonNull(exporter));
    }

    private void run(String metric, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            Level level = warned.compareAndSet(false, true) ? Level.WARNING : Level.FINE;
            logger.log(level, "MetricsExporter " + delegate.getClass().getName() + " failed in " + metric
                    + "; delivery continues (further failures are logged at FINE)", e);
        }
    }

    @Override
    public void incrementHotEnqueued() {
        run("incrementHotEnqueued", delegate::incrementHotEnqueued);
    }

    @Override
    public void incrementHotDropped() {
        run("incrementHotDropped", delegate::incrementHotDropped);
    }

    @Override
    public void incrementColdEnqueued() {
        run("incrementColdEnqueued", delegate::incrementColdEnqueued);
    }

    @Override
    public void incrementDispatchSuccess() {
        run("incrementDispatchSuccess", delegate::incrementDispatchSuccess);
    }

    @Override
    public void incrementDispatchFailure() {
        run("incrementDispatchFailure", delegate::incrementDispatchFailure);
    }

    @Override
    public void incrementDispatchDead() {
        run("incrementDispatchDead", delegate::incrementDispatchDead);
    }

    @Override
    public void incrementDispatchDeferred() {
        run("incrementDispatchDeferred", delegate::incrementDispatchDeferred);
    }

    @Override
    public void incrementHotSkippedDelayed() {
        run("incrementHotSkippedDelayed", delegate::incrementHotSkippedDelayed);
    }

    @Override
    public void incrementHotSkippedStale() {
        run("incrementHotSkippedStale", delegate::incrementHotSkippedStale);
    }

    @Override
    public void incrementHotTripped() {
        run("incrementHotTripped", delegate::incrementHotTripped);
    }

    @Override
    public void incrementDispatchSuppressed() {
        run("incrementDispatchSuppressed", delegate::incrementDispatchSuppressed);
    }

    @Override
    public void recordQueueDepths(int hotDepth, int coldDepth) {
        run("recordQueueDepths", () -> delegate.recordQueueDepths(hotDepth, coldDepth));
    }

    @Override
    public void bindQueueDepths(IntSupplier hotDepth, IntSupplier coldDepth) {
        run("bindQueueDepths", () -> delegate.bindQueueDepths(hotDepth, coldDepth));
    }

    @Override
    public void recordOldestLagMs(long lagMs) {
        run("recordOldestLagMs", () -> delegate.recordOldestLagMs(lagMs));
    }

    @Override
    public void recordDispatchLatencyMs(long latencyMs) {
        run("recordDispatchLatencyMs", () -> delegate.recordDispatchLatencyMs(latencyMs));
    }

    @Override
    public void recordListenerDurationMs(long durationMs) {
        run("recordListenerDurationMs", () -> delegate.recordListenerDurationMs(durationMs));
    }
}
