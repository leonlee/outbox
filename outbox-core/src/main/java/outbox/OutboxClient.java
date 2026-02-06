package outbox;

import outbox.dispatch.OutboxDispatcher;
import outbox.dispatch.QueuedEvent;
import outbox.spi.EventStore;
import outbox.spi.MetricsExporter;
import outbox.spi.TxContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OutboxClient {
  private static final Logger logger = Logger.getLogger(OutboxClient.class.getName());

  private final TxContext txContext;
  private final EventStore eventStore;
  private final OutboxDispatcher dispatcher;
  private final MetricsExporter metrics;

  public OutboxClient(TxContext txContext, EventStore eventStore, OutboxDispatcher dispatcher) {
    this(txContext, eventStore, dispatcher, null);
  }

  public OutboxClient(
      TxContext txContext,
      EventStore eventStore,
      OutboxDispatcher dispatcher,
      MetricsExporter metrics
  ) {
    this.txContext = Objects.requireNonNull(txContext, "txContext");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.metrics = metrics == null ? MetricsExporter.NOOP : metrics;
  }

  public String publish(EventEnvelope event) {
    if (!txContext.isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    Objects.requireNonNull(event, "event");

    Connection conn = txContext.currentConnection();
    eventStore.insertNew(conn, event);

    txContext.afterCommit(() -> {
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
    });

    return event.eventId();
  }

  public String publish(String eventType, String payloadJson) {
    return publish(EventEnvelope.ofJson(eventType, payloadJson));
  }

  public String publish(EventType eventType, String payloadJson) {
    return publish(EventEnvelope.ofJson(eventType, payloadJson));
  }

  public List<String> publishAll(List<EventEnvelope> events) {
    if (!txContext.isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    Objects.requireNonNull(events, "events");
    List<String> ids = new ArrayList<>(events.size());
    for (EventEnvelope event : events) {
      ids.add(publish(event));
    }
    return ids;
  }
}
