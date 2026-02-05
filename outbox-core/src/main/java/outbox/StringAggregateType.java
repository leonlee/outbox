package outbox;

import java.util.Objects;

/**
 * A simple string-based aggregate type for dynamic scenarios.
 *
 * <p>Use this when aggregate types are determined at runtime or when
 * you prefer string-based configuration over enums.
 *
 * <pre>{@code
 * AggregateType type = StringAggregateType.of("Order");
 * EventEnvelope envelope = EventEnvelope.builder(eventType)
 *     .aggregateType(type)
 *     .aggregateId("123")
 *     .payloadJson("{}")
 *     .build();
 * }</pre>
 */
public final class StringAggregateType implements AggregateType {

  private final String name;

  private StringAggregateType(String name) {
    this.name = Objects.requireNonNull(name, "name");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("Aggregate type name cannot be empty");
    }
  }

  /**
   * Creates an aggregate type from a string.
   *
   * @param name the aggregate type name
   * @return the aggregate type
   * @throws NullPointerException if name is null
   * @throws IllegalArgumentException if name is empty
   */
  public static StringAggregateType of(String name) {
    return new StringAggregateType(name);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof StringAggregateType)) return false;
    StringAggregateType that = (StringAggregateType) o;
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
