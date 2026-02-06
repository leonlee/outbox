package outbox.jdbc.dialect;

import outbox.jdbc.JdbcTemplate;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * MySQL dialect. Also compatible with TiDB.
 */
public final class MySqlDialect extends AbstractDialect {

  @Override
  public String name() {
    return "mysql";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:mysql:", "jdbc:tidb:");
  }

  @Override
  public List<OutboxEvent> claimPending(Connection conn, String table, String ownerId,
      Instant now, Instant lockExpiry, Instant recentCutoff, int limit) {
    // MySQL supports UPDATE...ORDER BY...LIMIT (no subquery needed)
    String claimSql = "UPDATE " + table + " SET locked_by=?, locked_at=? " +
        "WHERE status IN (0,2) AND available_at <= ?" +
        " AND (locked_by IS NULL OR locked_at < ?)" +
        " AND created_at <= ? ORDER BY created_at LIMIT ?";
    int updated = JdbcTemplate.update(conn, claimSql,
        ownerId, Timestamp.from(now), Timestamp.from(now),
        Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
    if (updated == 0) return List.of();
    String selectSql = "SELECT event_id, event_type, aggregate_type, aggregate_id, " +
        "tenant_id, payload, headers, attempts, created_at " +
        "FROM " + table + " WHERE locked_by=? ORDER BY created_at";
    return JdbcTemplate.query(conn, selectSql, EVENT_ROW_MAPPER, ownerId);
  }
}
