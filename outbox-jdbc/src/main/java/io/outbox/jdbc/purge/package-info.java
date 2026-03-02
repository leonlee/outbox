/**
 * JDBC-based {@link io.outbox.spi.EventPurger} implementations.
 *
 * <p>Two hierarchies are provided:
 * <ul>
 *   <li><b>Status-based</b> — {@link io.outbox.jdbc.purge.AbstractJdbcEventPurger} deletes only
 *       terminal events (DONE + DEAD) older than a cutoff. Subclasses: H2, MySQL
 *       ({@code DELETE...ORDER BY...LIMIT}), PostgreSQL.</li>
 *   <li><b>Age-based</b> — {@link io.outbox.jdbc.purge.AbstractJdbcAgeBasedPurger} deletes all
 *       events regardless of status (for CDC mode where no dispatcher marks DONE).
 *       Subclasses: H2, MySQL, PostgreSQL.</li>
 * </ul>
 *
 * @see io.outbox.jdbc.purge.AbstractJdbcEventPurger
 * @see io.outbox.jdbc.purge.AbstractJdbcAgeBasedPurger
 * @see io.outbox.jdbc.purge.H2EventPurger
 * @see io.outbox.jdbc.purge.MySqlEventPurger
 * @see io.outbox.jdbc.purge.PostgresEventPurger
 * @see io.outbox.jdbc.purge.H2AgeBasedPurger
 * @see io.outbox.jdbc.purge.MySqlAgeBasedPurger
 * @see io.outbox.jdbc.purge.PostgresAgeBasedPurger
 */
package io.outbox.jdbc.purge;
