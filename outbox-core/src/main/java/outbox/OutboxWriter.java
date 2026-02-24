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
 * {@link WriterHook} is invoked to trigger hot-path processing.
 *
 * @see WriterHook
 * @see outbox.spi.TxContext
 * @see outbox.spi.OutboxStore
 */
public final class OutboxWriter {
    private static final Logger logger = Logger.getLogger(OutboxWriter.class.getName());

    private final TxContext txContext;
    private final OutboxStore outboxStore;
    private final WriterHook writerHook;

    /**
     * Creates a writer with no writer hook (poller-only mode).
     *
     * @param txContext   transaction context for connection and lifecycle management
     * @param outboxStore persistence backend for outbox events
     */
    public OutboxWriter(TxContext txContext, OutboxStore outboxStore) {
        this(txContext, outboxStore, WriterHook.NOOP);
    }

    /**
     * Creates a writer with a writer hook for hot-path dispatch.
     *
     * @param txContext   transaction context for connection and lifecycle management
     * @param outboxStore persistence backend for outbox events
     * @param writerHook  lifecycle hook; {@code null} defaults to {@link WriterHook#NOOP}
     */
    public OutboxWriter(
            TxContext txContext,
            OutboxStore outboxStore,
            WriterHook writerHook
    ) {
        this.txContext = Objects.requireNonNull(txContext, "txContext");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.writerHook = writerHook == null ? WriterHook.NOOP : writerHook;
    }

    /**
     * Writes a single event to the outbox within the current transaction.
     *
     * @param event the event envelope to persist
     * @return the event ID (ULID), or {@code null} if the write was suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    public String write(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        List<String> ids = writeAll(List.of(event));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Convenience method to write a JSON event with a string event type.
     *
     * @param eventType   the event type name
     * @param payloadJson the JSON payload
     * @return the event ID (ULID), or {@code null} if suppressed by {@link WriterHook#beforeWrite}
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
     * @return the event ID (ULID), or {@code null} if suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    public String write(EventType eventType, String payloadJson) {
        return write(EventEnvelope.ofJson(eventType, payloadJson));
    }

    /**
     * Writes multiple events to the outbox within the current transaction.
     *
     * <p>Events are inserted in a single batch. A single {@code afterCommit} / {@code afterRollback}
     * callback is registered for the entire batch. The {@link WriterHook#beforeWrite} hook
     * may transform the event list before insertion.
     *
     * @param events the event envelopes to persist
     * @return list of event IDs in the same order as the (possibly transformed) input;
     * empty if the write was suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    public List<String> writeAll(List<EventEnvelope> events) {
        if (!txContext.isTransactionActive()) {
            throw new IllegalStateException("No active transaction");
        }
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return List.of();
        }

        // beforeWrite: may transform or suppress the list; throwing aborts the write
        List<EventEnvelope> transformed = writerHook.beforeWrite(List.copyOf(events));
        if (transformed == null || transformed.isEmpty()) {
            return List.of();
        }
        // Defensive copy once to prevent mutations by hook between insert and callbacks
        List<EventEnvelope> written = List.copyOf(transformed);

        // Insert batch
        Connection conn = txContext.currentConnection();
        outboxStore.insertBatch(conn, written);

        // afterWrite: observational
        runSafely("afterWrite", () -> writerHook.afterWrite(written));

        // Collect IDs
        List<String> ids = new ArrayList<>(written.size());
        for (EventEnvelope event : written) {
            ids.add(event.eventId());
        }
        if (writerHook != WriterHook.NOOP) {
            txContext.afterCommit(() -> runSafely("afterCommit", () -> writerHook.afterCommit(written)));
            txContext.afterRollback(() -> runSafely("afterRollback", () -> writerHook.afterRollback(written)));
        }

        return ids;
    }

    private void runSafely(String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "WriterHook." + phase + " failed", ex);
        }
    }
}
