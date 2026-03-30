package io.outbox;

/**
 * Signals a transient failure that may succeed on retry.
 *
 * <p>The dispatcher treats this (and its subclasses, e.g. {@link RetryAfterException})
 * like any other retryable exception — the event stays eligible for retry until
 * {@code maxAttempts} is exhausted.
 *
 * @see RetryAfterException
 */
public class RecoverableException extends EventException {

    public RecoverableException(String message) {
        super(message);
    }

    public RecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
