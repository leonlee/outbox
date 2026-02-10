package outbox.jdbc;

/**
 * Unchecked exception wrapping JDBC errors thrown by {@link AbstractJdbcEventStore}
 * and its subclasses.
 */
public final class EventStoreException extends RuntimeException {
  public EventStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
