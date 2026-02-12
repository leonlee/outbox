/**
 * JDBC-based {@link outbox.spi.EventPurger} implementations for TTL-based
 * deletion of terminal events.
 *
 * <p>{@link outbox.jdbc.purge.AbstractJdbcEventPurger} provides a default
 * subquery-based {@code DELETE} that works for H2 and PostgreSQL.
 * {@link outbox.jdbc.purge.MySqlEventPurger} overrides with
 * {@code DELETE...ORDER BY...LIMIT}.
 *
 * @see outbox.jdbc.purge.AbstractJdbcEventPurger
 * @see outbox.jdbc.purge.H2EventPurger
 * @see outbox.jdbc.purge.MySqlEventPurger
 * @see outbox.jdbc.purge.PostgresEventPurger
 */
package outbox.jdbc.purge;
