package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.spi.AfterCommitHook;
import outbox.spi.MetricsExporter;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DispatcherCommitHook implements AfterCommitHook {
  private static final Logger logger = Logger.getLogger(DispatcherCommitHook.class.getName());

  private final OutboxDispatcher dispatcher;
  private final MetricsExporter metrics;

  public DispatcherCommitHook(OutboxDispatcher dispatcher) {
    this(dispatcher, null);
  }

  public DispatcherCommitHook(OutboxDispatcher dispatcher, MetricsExporter metrics) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.metrics = metrics == null ? MetricsExporter.NOOP : metrics;
  }

  @Override
  public void onCommit(EventEnvelope event) {
    try {
      boolean enqueued = dispatcher.enqueueHot(new QueuedEvent(event, QueuedEvent.Source.HOT, 0));
      if (enqueued) {
        metrics.incrementHotEnqueued();
      } else {
        metrics.incrementHotDropped();
        logger.log(Level.WARNING, "Hot queue full, falling back to poller for eventId={0}", event.eventId());
      }
    } catch (RuntimeException ex) {
      metrics.incrementHotDropped();
      logger.log(Level.WARNING, "Failed to enqueue hot event, falling back to poller for eventId=" + event.eventId(), ex);
    }
  }
}
