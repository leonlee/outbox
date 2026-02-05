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
public final class StringEventType implements EventType {

  private final String name;

  private StringEventType(String name) {
    this.name = Objects.requireNonNull(name, "name");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Event type name cannot be empty");
    }
  }

  /**
   * Creates an event type from a string.
   *
   * @param name the event type name
   * @return the event type
   * @throws NullPointerException if name is null
   * @throws IllegalArgumentException if name is empty
   */
  public static StringEventType of(String name) {
    return new StringEventType(name);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof StringEventType)) return false;
    StringEventType that = (StringEventType) o;
    return name.equals(that.name);
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public String toString() {
    return name;
  }
}
