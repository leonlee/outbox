package io.outbox;

/**
 * Signals a deterministic failure that will never succeed on retry.
 *
 * <p>When the dispatcher catches this exception (or any subclass such as
 * {@link PayloadParseException}), it marks the event as DEAD immediately
 * without incrementing the retry counter.
 *
 * @see PayloadParseException
 */
public class UnrecoverableException extends EventException {

    public UnrecoverableException(String message) {
        super(message);
    }

    public UnrecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
