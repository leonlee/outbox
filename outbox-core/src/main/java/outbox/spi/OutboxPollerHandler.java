package outbox.spi;

import outbox.EventEnvelope;

@FunctionalInterface
public interface OutboxPollerHandler {
  boolean handle(EventEnvelope event, int attempts);

  default boolean hasCapacity() {
    return true;
  }
}
