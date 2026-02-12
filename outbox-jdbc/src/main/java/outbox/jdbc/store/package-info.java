/**
 * JDBC-based {@link outbox.spi.OutboxStore} implementations.
 *
 * <p>{@link outbox.jdbc.store.AbstractJdbcOutboxStore} provides shared SQL and row mapping;
 * subclasses supply database-specific claim strategies: H2 (subquery),
 * MySQL ({@code UPDATE...ORDER BY...LIMIT}), and PostgreSQL
 * ({@code FOR UPDATE SKIP LOCKED}).
 *
 * @see outbox.jdbc.store.AbstractJdbcOutboxStore
 * @see outbox.jdbc.store.H2OutboxStore
 * @see outbox.jdbc.store.MySqlOutboxStore
 * @see outbox.jdbc.store.PostgresOutboxStore
 * @see outbox.jdbc.store.JdbcOutboxStores
 */
package outbox.jdbc.store;
