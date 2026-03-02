package io.outbox;

import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link OutboxWriter} that persists events via
 * {@link OutboxStore} within an active {@link TxContext} transaction.
 *
 * <p>After the transaction commits, the configured {@link WriterHook} is
 * invoked to trigger hot-path processing.
 *
 * @see OutboxWriter
 * @see WriterHook
 * @see io.outbox.spi.TxContext
 * @see io.outbox.spi.OutboxStore
 */
public final class DefaultOutboxWriter implements OutboxWriter {
    private static final Logger logger = Logger.getLogger(DefaultOutboxWriter.class.getName());

    private final TxContext txContext;
    private final OutboxStore outboxStore;
    private final WriterHook writerHook;

    /**
     * Creates a writer with no writer hook (poller-only mode).
     *
     * @param txContext   transaction context for connection and lifecycle management
     * @param outboxStore persistence backend for outbox events
     */
    public DefaultOutboxWriter(TxContext txContext, OutboxStore outboxStore) {
        this(txContext, outboxStore, WriterHook.NOOP);
    }

    /**
     * Creates a writer with a writer hook for hot-path dispatch.
     *
     * @param txContext   transaction context for connection and lifecycle management
     * @param outboxStore persistence backend for outbox events
     * @param writerHook  lifecycle hook; {@code null} defaults to {@link WriterHook#NOOP}
     */
    public DefaultOutboxWriter(
            TxContext txContext,
            OutboxStore outboxStore,
            WriterHook writerHook
    ) {
        this.txContext = Objects.requireNonNull(txContext, "txContext");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.writerHook = writerHook == null ? WriterHook.NOOP : writerHook;
    }

    @Override
    public String write(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        List<String> ids = writeAll(List.of(event));
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public String write(String eventType, String payloadJson) {
        return write(EventEnvelope.ofJson(eventType, payloadJson));
    }

    @Override
    public String write(EventType eventType, String payloadJson) {
        return write(EventEnvelope.ofJson(eventType, payloadJson));
    }

    @Override
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
