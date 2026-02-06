package outbox.jdbc.dialect;

import outbox.jdbc.spi.Dialect;

/**
 * Base dialect with standard SQL implementations.
 *
 * <p>Subclasses can override methods to provide database-specific SQL.
 */
public abstract class AbstractDialect implements Dialect {

  @Override
  public String insertSql(String table) {
    return "INSERT INTO " + table + " (" +
        "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, status, attempts, available_at, created_at, done_at, last_error" +
        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)";
  }

  @Override
  public String markDoneSql(String table) {
    return "UPDATE " + table + " SET status=1, done_at=? WHERE event_id=? AND status<>1";
  }

  @Override
  public String markRetrySql(String table) {
    return "UPDATE " + table + " SET status=2, attempts=attempts+1, available_at=?, last_error=? " +
        "WHERE event_id=? AND status<>1";
  }

  @Override
  public String markDeadSql(String table) {
    return "UPDATE " + table + " SET status=3, last_error=? WHERE event_id=? AND status<>1";
  }

  @Override
  public String pollPendingSql(String table) {
    return "SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, attempts, created_at " +
        "FROM " + table + " WHERE status IN (0,2) AND available_at <= ? AND created_at <= ? " +
        "ORDER BY created_at LIMIT ?";
  }
}
