package outbox.jdbc.spi;

import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.time.Instant;
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

  /**
   * Claim and return pending events atomically.
   *
   * <p>Sets {@code locked_by} and {@code locked_at} on claimed rows.
   * Default implementation returns empty (no locking).
   *
   * @param conn        JDBC connection
   * @param table       table name
   * @param ownerId     poller instance identifier
   * @param now         current time
   * @param lockExpiry  locks older than this are considered expired
   * @param recentCutoff events created after this are skipped
   * @param limit       max rows to claim
   * @return claimed events
   */
  default List<OutboxEvent> claimPending(
      Connection conn, String table, String ownerId,
      Instant now, Instant lockExpiry, Instant recentCutoff, int limit) {
    return List.of();
  }
}
