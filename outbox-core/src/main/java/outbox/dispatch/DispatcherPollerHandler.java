package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.poller.OutboxPollerHandler;

import java.util.Objects;

/**
 * Bridges {@link outbox.poller.OutboxPoller} to the dispatcher's cold queue by implementing
 * {@link OutboxPollerHandler}. Polled events are forwarded to {@link OutboxDispatcher#enqueueCold}.
 *
 * @see OutboxDispatcher
 * @see OutboxPollerHandler
 */
public final class DispatcherPollerHandler implements OutboxPollerHandler {
  private final OutboxDispatcher dispatcher;

  public DispatcherPollerHandler(OutboxDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  @Override
  public int availableCapacity() {
    return dispatcher.coldQueueRemainingCapacity();
  }

  @Override
  public boolean handle(EventEnvelope event, int attempts) {
    QueuedEvent queuedEvent = new QueuedEvent(event, QueuedEvent.Source.COLD, attempts);
    return dispatcher.enqueueCold(queuedEvent);
  }
}
