package io.outbox.registry;

import io.outbox.EventListener;

/**
 * Registry for looking up event listeners by (aggregateType, eventType).
 *
 * <p>Each (aggregateType, eventType) pair maps to at most one listener.
 * The dispatcher uses this registry to find the listener that should
 * process a given event. If no listener is found, the event is marked DEAD.
 *
 * @see EventListener
 * @see DefaultListenerRegistry
 */
public interface ListenerRegistry {

    /**
     * Returns the single listener for the given (aggregateType, eventType),
     * or null if none registered.
     *
     * @param aggregateType the aggregate type
     * @param eventType     the event type
     * @return the listener, or null if unregistered
     */
    EventListener listenerFor(String aggregateType, String eventType);
}
