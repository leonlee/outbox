package outbox.dispatch;

import outbox.EventEnvelope;

/**
 * Internal wrapper pairing an {@link EventEnvelope} with its origin queue and attempt count.
 * Used by {@link OutboxDispatcher} to track events through the dual-queue processing pipeline.
 */
public record QueuedEvent(EventEnvelope envelope, Source source, int attempts) {

  /** Indicates whether an event arrived via the hot path or cold (poller) path. */
  public enum Source {
    /** Event enqueued directly via after-commit hook. */
    HOT,
    /** Event enqueued by the poller from the database. */
    COLD
  }
}
