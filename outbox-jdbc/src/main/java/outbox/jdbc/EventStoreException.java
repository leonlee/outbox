package outbox.jdbc;

public final class EventStoreException extends RuntimeException {
  public EventStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
