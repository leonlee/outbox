/**
 * JDBC infrastructure shared across sub-packages.
 *
 * <p>{@link outbox.jdbc.JdbcTemplate} provides lightweight JDBC helpers.
 * {@link outbox.jdbc.DataSourceConnectionProvider} adapts a {@link javax.sql.DataSource}
 * to the {@link outbox.spi.ConnectionProvider} SPI.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code outbox.jdbc.store} — {@link outbox.spi.OutboxStore} implementations</li>
 *   <li>{@code outbox.jdbc.purge} — {@link outbox.spi.EventPurger} implementations</li>
 *   <li>{@code outbox.jdbc.tx} — manual transaction management</li>
 * </ul>
 *
 * @see outbox.jdbc.JdbcTemplate
 * @see outbox.jdbc.DataSourceConnectionProvider
 */
package outbox.jdbc;
