package outbox.dispatch;

import outbox.EventEnvelope;

public record QueuedEvent(EventEnvelope envelope, Source source, int attempts) {
  public enum Source {
    HOT,
    COLD
  }
}
