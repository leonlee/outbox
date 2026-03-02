/**
 * JDBC infrastructure shared across sub-packages.
 *
 * <p>{@link io.outbox.jdbc.JdbcTemplate} provides lightweight JDBC helpers.
 * {@link io.outbox.jdbc.DataSourceConnectionProvider} adapts a {@link javax.sql.DataSource}
 * to the {@link io.outbox.spi.ConnectionProvider} SPI.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code io.outbox.jdbc.store} — {@link io.outbox.spi.OutboxStore} implementations</li>
 *   <li>{@code io.outbox.jdbc.purge} — {@link io.outbox.spi.EventPurger} implementations</li>
 *   <li>{@code io.outbox.jdbc.tx} — manual transaction management</li>
 * </ul>
 *
 * @see io.outbox.jdbc.JdbcTemplate
 * @see io.outbox.jdbc.DataSourceConnectionProvider
 */
package io.outbox.jdbc;
