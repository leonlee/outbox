package outbox.jdbc;

public final class OutboxRepositoryException extends RuntimeException {
  public OutboxRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
