package io.outbox.dispatch;

/**
 * Thrown when no listener is registered for an event's
 * (aggregateType, eventType) combination.
 *
 * <p>The dispatcher treats this as a non-retryable failure and
 * immediately marks the event as DEAD.
 */
public final class UnroutableEventException extends Exception {

    public UnroutableEventException(String message) {
        super(message);
    }
}
