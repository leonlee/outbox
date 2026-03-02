package io.outbox.jdbc;

/**
 * Unchecked exception wrapping JDBC errors thrown by {@link io.outbox.jdbc.store.AbstractJdbcOutboxStore}
 * and its subclasses.
 */
public final class OutboxStoreException extends RuntimeException {
    public OutboxStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
