package outbox.jdbc.dialect;

import outbox.jdbc.JdbcTemplate;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL dialect.
 */
public final class PostgresDialect extends AbstractDialect {

  @Override
  public String name() {
    return "postgresql";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:postgresql:");
  }

  @Override
  public List<OutboxEvent> claimPending(Connection conn, String table, String ownerId,
      Instant now, Instant lockExpiry, Instant recentCutoff, int limit) {
    // Single round-trip: FOR UPDATE SKIP LOCKED + RETURNING
    String sql = "UPDATE " + table + " SET locked_by=?, locked_at=? " +
        "WHERE event_id IN (" +
        "SELECT event_id FROM " + table +
        " WHERE status IN (0,2) AND available_at <= ?" +
        " AND (locked_by IS NULL OR locked_at < ?)" +
        " AND created_at <= ? ORDER BY created_at LIMIT ?" +
        " FOR UPDATE SKIP LOCKED" +
        ") RETURNING event_id, event_type, aggregate_type, aggregate_id, " +
        "tenant_id, payload, headers, attempts, created_at";
    return JdbcTemplate.updateReturning(conn, sql, EVENT_ROW_MAPPER,
        ownerId, Timestamp.from(now), Timestamp.from(now),
        Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
  }
}
