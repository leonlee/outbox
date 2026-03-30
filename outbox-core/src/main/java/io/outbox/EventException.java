package io.outbox;

/**
 * Base class for all outbox event processing exceptions.
 *
 * <p>Subclasses split into two branches:
 * <ul>
 *   <li>{@link RecoverableException} — transient failures eligible for retry</li>
 *   <li>{@link UnrecoverableException} — deterministic failures that should go straight to DEAD</li>
 * </ul>
 */
public class EventException extends RuntimeException {

    public EventException(String message) {
        super(message);
    }

    public EventException(String message, Throwable cause) {
        super(message, cause);
    }
}
