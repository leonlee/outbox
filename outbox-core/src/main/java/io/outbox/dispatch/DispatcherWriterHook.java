package io.outbox.dispatch;

import io.outbox.EventEnvelope;
import io.outbox.WriterHook;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import io.outbox.util.IsolatingMetricsExporter;

import java.time.Duration;
import java.time.Instant;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges {@link io.outbox.OutboxWriter} to the dispatcher's hot queue by implementing
 * {@link WriterHook}. When the transaction commits, each event is offered individually
 * to {@link OutboxDispatcher#enqueueHot}.
 *
 * @see OutboxDispatcher
 * @see WriterHook
 */
public final class DispatcherWriterHook implements WriterHook {
    private static final Logger logger = Logger.getLogger(DispatcherWriterHook.class.getName());

    /**
     * A saturated hot queue refuses every committed event, so a warning per refusal grows with
     * write throughput. One per interval names the refusals it stood for; the hot-dropped metric
     * still counts each one.
     */
    static final Duration REFUSAL_WARNING_INTERVAL = Duration.ofSeconds(30);

    private final OutboxDispatcher dispatcher;
    private final MetricsExporter metrics;
    private final BooleanSupplier open;
    private final LongSupplier nanoClock;
    private final Object refusalWarningLock = new Object();
    private long nextRefusalWarningAt; // guarded by refusalWarningLock
    private long suppressedRefusals; // guarded by refusalWarningLock

    public DispatcherWriterHook(OutboxDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public DispatcherWriterHook(OutboxDispatcher dispatcher, MetricsExporter metrics) {
        this(dispatcher, metrics, () -> true);
    }

    /**
     * @param dispatcher the dispatcher to feed
     * @param metrics    optional metrics exporter
     * @param open       whether hot delivery is open yet; while {@code false}, committed events
     *                   are left for the poller (used to hold the hot path until listeners exist)
     */
    public DispatcherWriterHook(OutboxDispatcher dispatcher, MetricsExporter metrics, BooleanSupplier open) {
        this(dispatcher, metrics, open, System::nanoTime);
    }

    DispatcherWriterHook(OutboxDispatcher dispatcher, MetricsExporter metrics, BooleanSupplier open,
                         LongSupplier nanoClock) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.metrics = IsolatingMetricsExporter.of(metrics);
        this.open = Objects.requireNonNull(open, "open");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.nextRefusalWarningAt = nanoClock.getAsLong();
    }

    @Override
    public void afterCommit(List<EventEnvelope> events) {
        if (!open.getAsBoolean()) {
            logger.log(Level.FINE, () -> "Hot path not open yet (outbox not started); poller will deliver "
                    + events.size() + " event(s)");
            return;
        }
        for (EventEnvelope event : events) {
            if (event.isDelayed()) {
                metrics.incrementHotSkippedDelayed();
                logger.log(Level.FINE,
                        "Skipping delayed event for hot queue, poller will deliver at availableAt; eventId="
                                + event.eventId());
                continue;
            }
            if (event.occurredAt().isBefore(Instant.now().minus(OutboxStore.WRITER_STAMP_MAX_AGE))) {
                // The store inserts events this stale UNOWNED — a writer stamp that old reads as an
                // expired lease to every poller, so writing it would reserve nothing. An unowned
                // row is claimable by any node at once, and racing a hot copy against those claims
                // recreates the cross-node duplicate the stamp exists to prevent. The poller's
                // claim is exclusive; a backfill has no latency to lose. Counted apart from the
                // delayed skip — a backfill burst inflating the delayed counter would read as a
                // scheduling problem that does not exist.
                metrics.incrementHotSkippedStale();
                logger.log(Level.FINE,
                        "Skipping stale event for hot queue (occurredAt older than "
                                + OutboxStore.WRITER_STAMP_MAX_AGE + "), poller will deliver; eventId="
                                + event.eventId());
                continue;
            }
            try {
                QueuedEvent queued = new QueuedEvent(event, QueuedEvent.Source.HOT, 0);
                boolean enqueued = dispatcher.enqueueHot(queued);
                if (enqueued) {
                    metrics.incrementHotEnqueued();
                } else {
                    metrics.incrementHotDropped();
                    // Deliberately does not say "full": enqueueHot also refuses when its
                    // head-of-line age trips the breaker, and a boolean cannot tell us which.
                    // The dispatcher logs the trip itself; outbox.enqueue.hot.tripped splits them.
                    warnRefused(event.eventId());
                }
            } catch (RuntimeException ex) {
                metrics.incrementHotDropped();
                logger.log(Level.WARNING,
                        "Failed to enqueue hot event, falling back to poller for eventId=" + event.eventId(), ex);
            }
        }
    }

    private void warnRefused(String eventId) {
        final long suppressed;
        synchronized (refusalWarningLock) {
            final long now = nanoClock.getAsLong();
            if (now - nextRefusalWarningAt < 0) {
                suppressedRefusals++;
                return;
            }
            nextRefusalWarningAt = now + REFUSAL_WARNING_INTERVAL.toNanos();
            suppressed = suppressedRefusals;
            suppressedRefusals = 0;
        }
        logger.log(Level.WARNING, "Hot queue did not accept event (full or throttled), "
                + "falling back to poller for eventId=" + eventId
                + (suppressed == 0 ? "" : "; " + suppressed + " more fell back since the last warning")
                + " (at most one warning per " + REFUSAL_WARNING_INTERVAL.toSeconds() + "s)");
    }
}
