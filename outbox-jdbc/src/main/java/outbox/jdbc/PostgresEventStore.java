package outbox.jdbc;

import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL event store.
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} with {@code RETURNING} for
 * single-round-trip claim.
 */
public final class PostgresEventStore extends AbstractJdbcEventStore {

  public PostgresEventStore() {
    super();
  }

  public PostgresEventStore(String tableName) {
    super(tableName);
  }

  @Override
  public String name() {
    return "postgresql";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:postgresql:");
  }

  @Override
  public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
      Instant lockExpiry, Duration skipRecent, int limit) {
    Instant recentCutoff = recentCutoff(now, skipRecent);
    // Single round-trip: FOR UPDATE SKIP LOCKED + RETURNING
    String sql = "UPDATE " + tableName() + " SET locked_by=?, locked_at=? " +
        "WHERE event_id IN (" +
        "SELECT event_id FROM " + tableName() +
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
