package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.poller.OutboxPollerHandler;

import java.util.Objects;

public final class DispatcherPollerHandler implements OutboxPollerHandler {
  private final OutboxDispatcher dispatcher;

  public DispatcherPollerHandler(OutboxDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public boolean hasCapacity() {
    return dispatcher.hasColdQueueCapacity();
  }

  @Override
  public boolean handle(EventEnvelope event, int attempts) {
    QueuedEvent queuedEvent = new QueuedEvent(event, QueuedEvent.Source.COLD, attempts);
    return dispatcher.enqueueCold(queuedEvent);
  }
}
