package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.WriterHook;
import outbox.spi.MetricsExporter;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges {@link outbox.OutboxWriter} to the dispatcher's hot queue by implementing
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
            try {
                QueuedEvent queued = new QueuedEvent(event, QueuedEvent.Source.HOT, 0);
                boolean enqueued = dispatcher.enqueueHot(queued);
                if (enqueued) {
                    metrics.incrementHotEnqueued();
                } else {
                    metrics.incrementHotDropped();
                    logger.log(Level.WARNING,
                            "Hot queue full, falling back to poller for eventId=" + event.eventId());
                }
            } catch (RuntimeException ex) {
                metrics.incrementHotDropped();
                logger.log(Level.WARNING,
                        "Failed to enqueue hot event, falling back to poller for eventId=" + event.eventId(), ex);
            }
        }
    }
}
