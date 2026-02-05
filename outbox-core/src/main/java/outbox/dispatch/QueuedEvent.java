package outbox.dispatch;

import outbox.EventEnvelope;

public final class QueuedEvent {
  public enum Source {
    HOT,
    COLD
  }

  private final EventEnvelope envelope;
  private final Source source;
  private final int attempts;

  public QueuedEvent(EventEnvelope envelope, Source source, int attempts) {
    this.envelope = envelope;
    this.source = source;
    this.attempts = attempts;
  }

  public EventEnvelope envelope() {
    return envelope;
  }

  public Source source() {
    return source;
  }

  public int attempts() {
    return attempts;
  }
}
