package outbox;

import outbox.spi.EventStore;
import outbox.spi.AfterCommitHook;
import outbox.spi.TxContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OutboxWriter {
  private static final Logger logger = Logger.getLogger(OutboxWriter.class.getName());

  private final TxContext txContext;
  private final EventStore eventStore;
  private final AfterCommitHook afterCommitHook;

  public OutboxWriter(TxContext txContext, EventStore eventStore) {
    this(txContext, eventStore, null);
  }

  public OutboxWriter(
      TxContext txContext,
      EventStore eventStore,
      AfterCommitHook afterCommitHook
  ) {
    this.txContext = Objects.requireNonNull(txContext, "txContext");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.afterCommitHook = afterCommitHook;
  }

  public String write(EventEnvelope event) {
    if (!txContext.isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    Objects.requireNonNull(event, "event");

    Connection conn = txContext.currentConnection();
    eventStore.insertNew(conn, event);

    if (afterCommitHook != null && afterCommitHook != AfterCommitHook.NOOP) {
      txContext.afterCommit(() -> runAfterCommitHook(event));
    }

    return event.eventId();
  }

  public String write(String eventType, String payloadJson) {
    return write(EventEnvelope.ofJson(eventType, payloadJson));
  }

  public String write(EventType eventType, String payloadJson) {
    return write(EventEnvelope.ofJson(eventType, payloadJson));
  }

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
