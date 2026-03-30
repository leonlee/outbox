package io.outbox;

import io.outbox.registry.ListenerRegistry;

import java.util.Objects;

/**
 * An {@link EventListener} bound to a specific {@code (aggregateType, eventType)} pair.
 *
 * <p>Bound listeners are registered in a {@link ListenerRegistry} and invoked by an {@link io.outbox.dispatch.OutboxDispatcher}.
 *
 * @see ListenerRegistry
 * @see io.outbox.dispatch.OutboxDispatcher
 */
public abstract class BoundEventListener implements EventListener {
    private final String aggregateType;
    private final String eventType;

    /**
     * Constructs a BoundEventListener with the specified aggregate and event types.
     *
     * @param aggregateType the aggregate type this listener is bound to
     * @param eventType     the event type this listener is bound to
     */
    public BoundEventListener(String aggregateType, String eventType) {
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
    }

    /**
     * Constructs a BoundEventListener using enum names for aggregate and event types.
     *
     * @param aggregateType the aggregate type enum this listener is bound to
     * @param eventType     the event type enum this listener is bound to
     */
    public BoundEventListener(AggregateType aggregateType, EventType eventType) {
        this(aggregateType.name(), eventType.name());
    }

    /**
     * Returns the aggregate type this listener is bound to.
     *
     * @return the aggregate type
     */
    public String getAggregateType() {
        return aggregateType;
    }

    /**
     * Returns the event type this listener is bound to.
     *
     * @return the event type
     */
    public String getEventType() {
        return eventType;
    }
}
