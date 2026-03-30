package io.outbox.dispatch;

import io.outbox.UnrecoverableException;

/**
 * Thrown when no listener is registered for an event's
 * (aggregateType, eventType) combination.
 *
 * <p>This is an {@link UnrecoverableException}: unroutable events are marked
 * <strong>DEAD immediately</strong> without retry. Callers should ensure all
 * listeners are registered before the poller starts (e.g. via
 * {@link io.outbox.Outbox Outbox.deferStart()}).
 */
public final class UnroutableEventException extends UnrecoverableException {

    public UnroutableEventException(String message) {
        super(message);
    }
}
