package outbox.jdbc.spi;

import java.util.List;

/**
 * SPI for database dialect support.
 *
 * <p>Implementations provide database-specific SQL for outbox operations.
 * Register custom dialects via {@code META-INF/services/outbox.jdbc.spi.Dialect}.
 *
 * <p>Built-in dialects: MySQL (+ TiDB), PostgreSQL, H2.
 *
 * @see outbox.jdbc.dialect.Dialects
 */
public interface Dialect {

  /**
   * Unique identifier for this dialect (e.g., "mysql", "postgresql", "h2").
   */
  String name();

  /**
   * JDBC URL prefixes this dialect handles (e.g., "jdbc:mysql:", "jdbc:tidb:").
   */
  List<String> jdbcUrlPrefixes();

  /**
   * SQL for inserting a new outbox event.
   *
   * <p>Parameters (in order):
   * <ol>
   *   <li>event_id (String)</li>
   *   <li>event_type (String)</li>
   *   <li>aggregate_type (String)</li>
   *   <li>aggregate_id (String)</li>
   *   <li>tenant_id (String)</li>
   *   <li>payload (String/JSON)</li>
   *   <li>headers (String/JSON)</li>
   *   <li>status (int)</li>
   *   <li>attempts (int)</li>
   *   <li>available_at (Timestamp)</li>
   *   <li>created_at (Timestamp)</li>
   * </ol>
   */
  String insertSql(String table);

  /**
   * SQL for marking an event as DONE.
   *
   * <p>Parameters: done_at (Timestamp), event_id (String)
   */
  String markDoneSql(String table);

  /**
   * SQL for marking an event for RETRY.
   *
   * <p>Parameters: available_at (Timestamp), last_error (String), event_id (String)
   */
  String markRetrySql(String table);

  /**
   * SQL for marking an event as DEAD.
   *
   * <p>Parameters: last_error (String), event_id (String)
   */
  String markDeadSql(String table);

  /**
   * SQL for polling pending events.
   *
   * <p>Parameters: available_at (Timestamp), created_at (Timestamp), limit (int)
   *
   * <p>Returns columns: event_id, event_type, aggregate_type, aggregate_id,
   * tenant_id, payload, headers, attempts, created_at
   */
  String pollPendingSql(String table);
}
