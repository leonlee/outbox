package outbox.jdbc;

import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * MySQL event store. Also compatible with TiDB.
 *
 * <p>Uses {@code UPDATE...ORDER BY...LIMIT} for claim (no subquery needed).
 */
public final class MySqlEventStore extends AbstractJdbcEventStore {

  public MySqlEventStore() {
    super();
  }

  public MySqlEventStore(String tableName) {
    super(tableName);
  }

  @Override
  public String name() {
    return "mysql";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:mysql:", "jdbc:tidb:");
  }

  @Override
  public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
      Instant lockExpiry, Duration skipRecent, int limit) {
    Instant recentCutoff = recentCutoff(now, skipRecent);
    // MySQL supports UPDATE...ORDER BY...LIMIT (no subquery needed)
    String claimSql = "UPDATE " + tableName() + " SET locked_by=?, locked_at=? " +
        "WHERE status IN (0,2) AND available_at <= ?" +
        " AND (locked_by IS NULL OR locked_at < ?)" +
        " AND created_at <= ? ORDER BY created_at LIMIT ?";
    int updated = JdbcTemplate.update(conn, claimSql,
        ownerId, Timestamp.from(now), Timestamp.from(now),
        Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
    if (updated == 0) return List.of();
    String selectSql = "SELECT event_id, event_type, aggregate_type, aggregate_id, " +
        "tenant_id, payload, headers, attempts, created_at " +
        "FROM " + tableName() + " WHERE locked_by=? ORDER BY created_at";
    return JdbcTemplate.query(conn, selectSql, EVENT_ROW_MAPPER, ownerId);
  }
}
