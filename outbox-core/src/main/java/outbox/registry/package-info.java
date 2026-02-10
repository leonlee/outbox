/**
 * Listener routing by {@code (aggregateType, eventType)} pairs.
 *
 * <p>The registry maps each unique key to a single {@link outbox.EventListener}.
 * Events with no matching listener are treated as unroutable and marked DEAD.
 *
 * @see outbox.registry.ListenerRegistry
 * @see outbox.registry.DefaultListenerRegistry
 */
package outbox.registry;
