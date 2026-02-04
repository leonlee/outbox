package outbox.core.api;

/**
 * Represents an aggregate type identifier.
 *
 * <p>Implementations can be enums for compile-time safety:
 * <pre>{@code
 * public enum Aggregates implements AggregateType {
 *   USER,
 *   ORDER,
 *   PRODUCT;
 *
 *   @Override
 *   public String name() {
 *     return name(); // Enum.name() already returns the constant name
 *   }
 * }
 * }</pre>
 *
 * <p>Or use {@link StringAggregateType} for dynamic aggregate types:
 * <pre>{@code
 * AggregateType type = StringAggregateType.of("DynamicAggregate");
 * }</pre>
 */
public interface AggregateType {

  /**
   * Returns the string representation of this aggregate type.
   * This value is persisted to the database.
   *
   * @return the aggregate type name, never null
   */
  String name();
}
