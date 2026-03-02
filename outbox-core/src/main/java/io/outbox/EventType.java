package io.outbox;

/**
 * Represents an event type identifier.
 *
 * <p>Implementations can be enums for compile-time safety:
 * <pre>{@code
 * public enum UserEvents implements EventType {
 *   USER_CREATED,
 *   USER_UPDATED,
 *   USER_DELETED;
 *   // No need to override name() — Enum.name() already satisfies the contract
 * }
 * }</pre>
 *
 * <p>Or use {@link StringEventType} for dynamic event types:
 * <pre>{@code
 * EventType type = StringEventType.of("DynamicEvent");
 * }</pre>
 */
public interface EventType {

    /**
     * Returns the string representation of this event type.
     * This value is persisted to the database and used for routing.
     *
     * @return the event type name, never null
     */
    default String name() {
        return this.getClass().getName();
    }
}
