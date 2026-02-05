package outbox.core.client;

import outbox.core.api.EventEnvelope;
import outbox.core.api.NoopOutboxMetrics;
import outbox.core.api.OutboxClient;
import outbox.core.api.OutboxMetrics;
import outbox.core.dispatch.OutboxDispatcher;
import outbox.core.dispatch.QueuedEvent;
import outbox.core.repo.OutboxRepository;
import outbox.core.tx.TxContext;

import java.sql.Connection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DefaultOutboxClient implements OutboxClient {
  private static final Logger logger = Logger.getLogger(DefaultOutboxClient.class.getName());

  private final TxContext txContext;
  private final OutboxRepository repository;
  private final OutboxDispatcher dispatcher;
  private final OutboxMetrics metrics;

  public DefaultOutboxClient(
      TxContext txContext,
      OutboxRepository repository,
      OutboxDispatcher dispatcher,
      OutboxMetrics metrics
  ) {
    this.txContext = Objects.requireNonNull(txContext, "txContext");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.metrics = metrics == null ? new NoopOutboxMetrics() : metrics;
  }

  @Override
  public String publish(EventEnvelope event) {
    if (!txContext.isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    Objects.requireNonNull(event, "event");

    Connection conn = txContext.currentConnection();
    repository.insertNew(conn, event);

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
}
