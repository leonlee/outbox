package io.outbox.dispatch;

import io.outbox.EventEnvelope;
import io.outbox.WriterHook;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;

import java.time.Instant;

import java.util.List;
import java.util.Objects;
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

    private final OutboxDispatcher dispatcher;
    private final MetricsExporter metrics;

    public DispatcherWriterHook(OutboxDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public DispatcherWriterHook(OutboxDispatcher dispatcher, MetricsExporter metrics) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.metrics = metrics == null ? MetricsExporter.NOOP : metrics;
    }

    @Override
    public void afterCommit(List<EventEnvelope> events) {
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
                    logger.log(Level.WARNING,
                            "Hot queue did not accept event (full or throttled), "
                                    + "falling back to poller for eventId=" + event.eventId());
                }
            } catch (RuntimeException ex) {
                metrics.incrementHotDropped();
                logger.log(Level.WARNING,
                        "Failed to enqueue hot event, falling back to poller for eventId=" + event.eventId(), ex);
            }
        }
    }
}
