package outbox;

import java.util.Objects;

/**
 * A simple string-based event type for dynamic scenarios.
 *
 * <p>Use this when event types are determined at runtime or when
 * you prefer string-based configuration over enums.
 *
 * <pre>{@code
 * EventType type = StringEventType.of("OrderPlaced");
 * EventEnvelope envelope = EventEnvelope.builder(type)
 *     .payloadJson("{}")
 *     .build();
 * }</pre>
 */
public record StringEventType(String name) implements EventType {

    /**
     * @param name the event type name
     * @throws NullPointerException     if name is null
     * @throws IllegalArgumentException if name is empty
     */
    public StringEventType {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Event type name cannot be empty");
        }
    }

    /**
     * Creates an event type from a string.
     *
     * @param name the event type name
     * @return the event type
     * @throws NullPointerException     if name is null
     * @throws IllegalArgumentException if name is empty
     */
    public static StringEventType of(String name) {
        return new StringEventType(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
