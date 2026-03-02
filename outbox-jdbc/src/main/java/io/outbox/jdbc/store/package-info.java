/**
 * JDBC-based {@link io.outbox.spi.OutboxStore} implementations.
 *
 * <p>{@link io.outbox.jdbc.store.AbstractJdbcOutboxStore} provides shared SQL and row mapping;
 * subclasses supply database-specific claim strategies: H2 (subquery),
 * MySQL ({@code UPDATE...ORDER BY...LIMIT}), and PostgreSQL
 * ({@code FOR UPDATE SKIP LOCKED}).
 *
 * @see io.outbox.jdbc.store.AbstractJdbcOutboxStore
 * @see io.outbox.jdbc.store.H2OutboxStore
 * @see io.outbox.jdbc.store.MySqlOutboxStore
 * @see io.outbox.jdbc.store.PostgresOutboxStore
 * @see io.outbox.jdbc.store.JdbcOutboxStores
 */
package io.outbox.jdbc.store;
