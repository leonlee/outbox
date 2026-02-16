package outbox.jdbc.store;

import outbox.jdbc.JdbcTemplate;
import outbox.model.OutboxEvent;
import outbox.util.JsonCodec;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * MySQL outbox store. Also compatible with TiDB.
 *
 * <p>Uses {@code UPDATE...ORDER BY...LIMIT} for claim followed by a
 * {@code SELECT} to return claimed rows (two-phase). This two-phase approach
 * is <strong>not fully atomic under concurrent access</strong> — two concurrent
 * pollers may see overlapping rows in the SELECT phase. For single-instance
 * deployments this is safe. For multi-instance deployments, prefer
 * {@link PostgresOutboxStore} which uses {@code FOR UPDATE SKIP LOCKED}
 * with {@code RETURNING} in a single round-trip.
 */
public final class MySqlOutboxStore extends AbstractJdbcOutboxStore {

  public MySqlOutboxStore() {
    super();
  }

  public MySqlOutboxStore(String tableName) {
    super(tableName);
  }

  public MySqlOutboxStore(String tableName, JsonCodec jsonCodec) {
    super(tableName, jsonCodec);
  }

  @Override
  public AbstractJdbcOutboxStore withJsonCodec(JsonCodec jsonCodec) {
    return new MySqlOutboxStore(tableName(), jsonCodec);
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
    Objects.requireNonNull(ownerId, "ownerId");
    // Truncate to millis so stored value matches query (DB may drop nanos)
    Instant nowMs = now.truncatedTo(ChronoUnit.MILLIS);
    Instant recentCutoff = recentCutoff(now, skipRecent);
    // MySQL supports UPDATE...ORDER BY...LIMIT (no subquery needed)
    String claimSql = "UPDATE " + tableName() + " SET locked_by=?, locked_at=? " +
        "WHERE status IN " + PENDING_STATUS_IN + " AND available_at <= ?" +
        " AND (locked_by IS NULL OR locked_at < ?)" +
        " AND created_at <= ? ORDER BY created_at LIMIT ?";
    int updated = JdbcTemplate.update(conn, claimSql,
        ownerId, Timestamp.from(nowMs), Timestamp.from(now),
        Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
    if (updated == 0) return List.of();
    return selectClaimed(conn, ownerId, nowMs);
  }
}
