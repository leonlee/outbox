package outbox.jdbc.dialect;

import outbox.jdbc.JdbcTemplate;
import outbox.jdbc.spi.Dialect;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Base dialect with standard SQL implementations.
 *
 * <p>Subclasses can override methods to provide database-specific SQL.
 */
public abstract class AbstractDialect implements Dialect {

  protected static final JdbcTemplate.RowMapper<OutboxEvent> EVENT_ROW_MAPPER = rs -> new OutboxEvent(
      rs.getString("event_id"),
      rs.getString("event_type"),
      rs.getString("aggregate_type"),
      rs.getString("aggregate_id"),
      rs.getString("tenant_id"),
      rs.getString("payload"),
      rs.getString("headers"),
      rs.getInt("attempts"),
      rs.getTimestamp("created_at").toInstant());

  @Override
  public String insertSql(String table) {
    return "INSERT INTO " + table + " (" +
        "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, status, attempts, available_at, created_at, done_at, last_error, " +
        "locked_by, locked_at" +
        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL)";
  }

  @Override
  public String markDoneSql(String table) {
    return "UPDATE " + table +
        " SET status=1, done_at=?, locked_by=NULL, locked_at=NULL" +
        " WHERE event_id=? AND status<>1";
  }

  @Override
  public String markRetrySql(String table) {
    return "UPDATE " + table +
        " SET status=2, attempts=attempts+1, available_at=?, last_error=?, locked_by=NULL, locked_at=NULL" +
        " WHERE event_id=? AND status<>1";
  }

  @Override
  public String markDeadSql(String table) {
    return "UPDATE " + table +
        " SET status=3, last_error=?, locked_by=NULL, locked_at=NULL" +
        " WHERE event_id=? AND status<>1";
  }

  @Override
  public String pollPendingSql(String table) {
    return "SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, attempts, created_at " +
        "FROM " + table + " WHERE status IN (0,2) AND available_at <= ? AND created_at <= ? " +
        "ORDER BY created_at LIMIT ?";
  }

  @Override
  public List<OutboxEvent> claimPending(Connection conn, String table, String ownerId,
      Instant now, Instant lockExpiry, Instant recentCutoff, int limit) {
    // Phase 1: UPDATE with subquery (H2-compatible default)
    String claimSql = "UPDATE " + table + " SET locked_by=?, locked_at=? " +
        "WHERE event_id IN (" +
        "SELECT event_id FROM " + table +
        " WHERE status IN (0,2) AND available_at <= ?" +
        " AND (locked_by IS NULL OR locked_at < ?)" +
        " AND created_at <= ? ORDER BY created_at LIMIT ?)";
    int updated = JdbcTemplate.update(conn, claimSql,
        ownerId, Timestamp.from(now), Timestamp.from(now),
        Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
    if (updated == 0) return List.of();
    // Phase 2: SELECT claimed rows
    String selectSql = "SELECT event_id, event_type, aggregate_type, aggregate_id, " +
        "tenant_id, payload, headers, attempts, created_at " +
        "FROM " + table + " WHERE locked_by=? ORDER BY created_at";
    return JdbcTemplate.query(conn, selectSql, EVENT_ROW_MAPPER, ownerId);
  }
}
