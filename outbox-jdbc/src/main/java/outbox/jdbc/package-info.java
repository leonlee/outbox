/**
 * JDBC-based {@link outbox.spi.EventStore} implementations.
 *
 * <p>{@link outbox.jdbc.AbstractJdbcEventStore} provides shared SQL and row mapping;
 * subclasses supply database-specific claim strategies: H2 (subquery),
 * MySQL ({@code UPDATE...ORDER BY...LIMIT}), and PostgreSQL
 * ({@code FOR UPDATE SKIP LOCKED}).
 *
 * @see outbox.jdbc.AbstractJdbcEventStore
 * @see outbox.jdbc.H2EventStore
 * @see outbox.jdbc.MySqlEventStore
 * @see outbox.jdbc.PostgresEventStore
 * @see outbox.jdbc.JdbcEventStores
 */
package outbox.jdbc;
