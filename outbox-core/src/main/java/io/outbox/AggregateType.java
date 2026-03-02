package io.outbox;

/**
 * Represents an aggregate type identifier.
 *
 * <p>Implementations can be enums for compile-time safety:
 * <pre>{@code
 * public enum Aggregates implements AggregateType {
 *   USER,
 *   ORDER,
 *   PRODUCT;
 *   // No need to override name() — Enum.name() already satisfies the contract
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
     * Global aggregate type used when no specific aggregate type is set.
     */
    AggregateType GLOBAL = new AggregateType() {
        @Override
        public String name() {
            return "__GLOBAL__";
        }

        @Override
        public String toString() {
            return "GLOBAL";
        }
    };

    /**
     * Returns the string representation of this aggregate type.
     * This value is persisted to the database.
     *
     * <p>Enum implementations inherit {@link Enum#name()} automatically.
     * Non-enum implementations (records, classes) must override this method
     * to return a stable, human-readable name.
     *
     * @return the aggregate type name, never null
     */
    String name();
}
