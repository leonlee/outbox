package outbox;

import outbox.spi.OutboxStore;
import outbox.spi.TxContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Primary entry point for writing events to the outbox within an active transaction.
 *
 * <p>All {@code write} and {@code writeAll} calls require an active transaction via
 * {@link outbox.spi.TxContext}. After the transaction commits, the configured
 * {@link AfterCommitHook} is invoked to trigger hot-path processing.
 *
 * @see AfterCommitHook
 * @see outbox.spi.TxContext
 * @see outbox.spi.OutboxStore
 */
public final class OutboxWriter {
  private static final Logger logger = Logger.getLogger(OutboxWriter.class.getName());

  private final TxContext txContext;
  private final OutboxStore outboxStore;
  private final AfterCommitHook afterCommitHook;

  /**
   * Creates a writer with no after-commit hook (poller-only mode).
   *
   * @param txContext   transaction context for connection and lifecycle management
   * @param outboxStore persistence backend for outbox events
   */
  public OutboxWriter(TxContext txContext, OutboxStore outboxStore) {
    this(txContext, outboxStore, AfterCommitHook.NOOP);
  }

  /**
   * Creates a writer with an after-commit hook for hot-path dispatch.
   *
   * @param txContext       transaction context for connection and lifecycle management
   * @param outboxStore     persistence backend for outbox events
   * @param afterCommitHook callback invoked after commit; {@code null} defaults to {@link AfterCommitHook#NOOP}
   */
  public OutboxWriter(
      TxContext txContext,
      OutboxStore outboxStore,
      AfterCommitHook afterCommitHook
  ) {
    this.txContext = Objects.requireNonNull(txContext, "txContext");
    this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
    this.afterCommitHook = afterCommitHook == null ? AfterCommitHook.NOOP : afterCommitHook;
  }

  /**
   * Writes a single event to the outbox within the current transaction.
   *
   * @param event the event envelope to persist
   * @return the event ID (ULID)
   * @throws IllegalStateException if no transaction is active
   */
  public String write(EventEnvelope event) {
    if (!txContext.isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    Objects.requireNonNull(event, "event");

    Connection conn = txContext.currentConnection();
    outboxStore.insertNew(conn, event);

    if (afterCommitHook != AfterCommitHook.NOOP) {
      txContext.afterCommit(() -> runAfterCommitHook(event));
    }

    return event.eventId();
  }

  /**
   * Convenience method to write a JSON event with a string event type.
   *
   * @param eventType   the event type name
   * @param payloadJson the JSON payload
   * @return the event ID (ULID)
   * @throws IllegalStateException if no transaction is active
   */
  public String write(String eventType, String payloadJson) {
    return write(EventEnvelope.ofJson(eventType, payloadJson));
  }

  /**
   * Convenience method to write a JSON event with a type-safe event type.
   *
   * @param eventType   the event type
   * @param payloadJson the JSON payload
   * @return the event ID (ULID)
   * @throws IllegalStateException if no transaction is active
   */
  public String write(EventType eventType, String payloadJson) {
    return write(EventEnvelope.ofJson(eventType, payloadJson));
  }

  /**
   * Writes multiple events to the outbox within the current transaction.
   *
   * @param events the event envelopes to persist
   * @return list of event IDs in the same order as the input
   * @throws IllegalStateException if no transaction is active
   */
  public List<String> writeAll(List<EventEnvelope> events) {
    if (!txContext.isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    Objects.requireNonNull(events, "events");
    List<String> ids = new ArrayList<>(events.size());
    for (EventEnvelope event : events) {
      ids.add(write(event));
    }
    return ids;
  }

  private void runAfterCommitHook(EventEnvelope event) {
    try {
      afterCommitHook.onCommit(event);
    } catch (RuntimeException ex) {
      logger.log(Level.WARNING, "After-commit hook failed for eventId=" + event.eventId(), ex);
    }
  }
}
