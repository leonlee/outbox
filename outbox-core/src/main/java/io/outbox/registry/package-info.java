/**
 * Listener routing by {@code (aggregateType, eventType)} pairs.
 *
 * <p>The registry maps each unique key to a single {@link io.outbox.EventListener}.
 * Events with no matching listener are treated as unroutable and marked DEAD.
 *
 * @see io.outbox.registry.ListenerRegistry
 * @see io.outbox.registry.DefaultListenerRegistry
 */
package io.outbox.registry;
