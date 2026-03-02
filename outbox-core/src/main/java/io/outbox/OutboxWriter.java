package io.outbox;

import java.util.List;

/**
 * Primary entry point for writing events to the outbox within an active transaction.
 *
 * <p>All {@code write} and {@code writeAll} calls require an active transaction via
 * {@link io.outbox.spi.TxContext}. After the transaction commits, the configured
 * {@link WriterHook} is invoked to trigger hot-path processing.
 *
 * <p>The default implementation is {@link DefaultOutboxWriter}. For testing without
 * a database, this interface can be easily mocked or stubbed.
 *
 * @see DefaultOutboxWriter
 * @see WriterHook
 * @see io.outbox.spi.TxContext
 * @see io.outbox.spi.OutboxStore
 */
public interface OutboxWriter {

    /**
     * Writes a single event to the outbox within the current transaction.
     *
     * @param event the event envelope to persist
     * @return the event ID (ULID), or {@code null} if the write was suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    String write(EventEnvelope event);

    /**
     * Convenience method to write a JSON event with a string event type.
     *
     * @param eventType   the event type name
     * @param payloadJson the JSON payload
     * @return the event ID (ULID), or {@code null} if suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    String write(String eventType, String payloadJson);

    /**
     * Convenience method to write a JSON event with a type-safe event type.
     *
     * @param eventType   the event type
     * @param payloadJson the JSON payload
     * @return the event ID (ULID), or {@code null} if suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    String write(EventType eventType, String payloadJson);

    /**
     * Writes multiple events to the outbox within the current transaction.
     *
     * <p>Events are inserted in a single batch. A single {@code afterCommit} / {@code afterRollback}
     * callback is registered for the entire batch. The {@link WriterHook#beforeWrite} hook
     * may transform the event list before insertion.
     *
     * @param events the event envelopes to persist
     * @return list of event IDs in the same order as the (possibly transformed) input;
     *         empty if the write was suppressed by {@link WriterHook#beforeWrite}
     * @throws IllegalStateException if no transaction is active
     */
    List<String> writeAll(List<EventEnvelope> events);
}
