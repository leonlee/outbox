/**
 * JDBC-based {@link io.outbox.spi.EventPurger} implementations for TTL-based
 * deletion of terminal events.
 *
 * <p>{@link io.outbox.jdbc.purge.AbstractJdbcEventPurger} provides a default
 * subquery-based {@code DELETE} that works for H2 and PostgreSQL.
 * {@link io.outbox.jdbc.purge.MySqlEventPurger} overrides with
 * {@code DELETE...ORDER BY...LIMIT}.
 *
 * @see io.outbox.jdbc.purge.AbstractJdbcEventPurger
 * @see io.outbox.jdbc.purge.H2EventPurger
 * @see io.outbox.jdbc.purge.MySqlEventPurger
 * @see io.outbox.jdbc.purge.PostgresEventPurger
 */
package io.outbox.jdbc.purge;
