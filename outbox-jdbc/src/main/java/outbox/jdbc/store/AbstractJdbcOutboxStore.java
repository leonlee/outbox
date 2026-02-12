package outbox.jdbc.store;

import outbox.EventEnvelope;
import outbox.jdbc.JdbcTemplate;
import outbox.model.EventStatus;
import outbox.model.OutboxEvent;
import outbox.spi.OutboxStore;
import outbox.util.JsonCodec;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Base JDBC outbox store with standard SQL implementations.
 *
 * <p>Subclasses override {@link #claimPending} to provide database-specific
 * claim strategies. Register custom implementations via
 * {@code META-INF/services/outbox.jdbc.store.AbstractJdbcOutboxStore}.
 *
 * @see JdbcOutboxStores
 */
public abstract class AbstractJdbcOutboxStore implements OutboxStore {
  protected static final String DEFAULT_TABLE = "outbox_event";
  private static final int MAX_ERROR_LENGTH = 4000;

  protected static final String PENDING_STATUS_IN =
      "(" + EventStatus.NEW.code() + "," + EventStatus.RETRY.code() + ")";

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

  private final String tableName;

  protected AbstractJdbcOutboxStore() {
    this(DEFAULT_TABLE);
  }

  protected AbstractJdbcOutboxStore(String tableName) {
    Objects.requireNonNull(tableName, "tableName");
    if (!tableName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      throw new IllegalArgumentException("Invalid table name: " + tableName);
    }
    this.tableName = tableName;
  }

  /**
   * Unique identifier for this outbox store (e.g., "mysql", "postgresql", "h2").
   */
  public abstract String name();

  /**
   * JDBC URL prefixes this outbox store handles (e.g., "jdbc:mysql:", "jdbc:tidb:").
   */
  public abstract List<String> jdbcUrlPrefixes();

  protected String tableName() {
    return tableName;
  }

  @Override
  public void insertNew(Connection conn, EventEnvelope event) {
    String sql = "INSERT INTO " + tableName() + " (" +
        "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, status, attempts, available_at, created_at, done_at, last_error, " +
        "locked_by, locked_at" +
        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL)";
    Timestamp now = Timestamp.from(event.occurredAt());
    JdbcTemplate.update(conn, sql,
        event.eventId(), event.eventType(), event.aggregateType(),
        event.aggregateId(), event.tenantId(), event.payloadJson(),
        JsonCodec.toJson(event.headers()),
        EventStatus.NEW.code(), 0, now, now);
  }

  @Override
  public int markDone(Connection conn, String eventId) {
    String sql = "UPDATE " + tableName() +
        " SET status=" + EventStatus.DONE.code() + ", done_at=?, locked_by=NULL, locked_at=NULL" +
        " WHERE event_id=? AND status<>" + EventStatus.DONE.code();
    return JdbcTemplate.update(conn, sql, Timestamp.from(Instant.now()), eventId);
  }

  @Override
  public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
    String sql = "UPDATE " + tableName() +
        " SET status=" + EventStatus.RETRY.code() +
        ", attempts=attempts+1, available_at=?, last_error=?, locked_by=NULL, locked_at=NULL" +
        " WHERE event_id=? AND status<>" + EventStatus.DONE.code();
    return JdbcTemplate.update(conn, sql, Timestamp.from(nextAt), truncateError(error), eventId);
  }

  @Override
  public int markDead(Connection conn, String eventId, String error) {
    String sql = "UPDATE " + tableName() +
        " SET status=" + EventStatus.DEAD.code() + ", last_error=?, locked_by=NULL, locked_at=NULL" +
        " WHERE event_id=? AND status<>" + EventStatus.DONE.code();
    return JdbcTemplate.update(conn, sql, truncateError(error), eventId);
  }

  @Override
  public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
    String sql = "SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, attempts, created_at " +
        "FROM " + tableName() + " WHERE status IN " + PENDING_STATUS_IN +
        " AND available_at <= ? AND created_at <= ? " +
        "ORDER BY created_at LIMIT ?";
    Instant recentCutoff = recentCutoff(now, skipRecent);
    return JdbcTemplate.query(conn, sql, EVENT_ROW_MAPPER,
        Timestamp.from(now), Timestamp.from(recentCutoff), limit);
  }

  @Override
  public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
      Instant lockExpiry, Duration skipRecent, int limit) {
    // Truncate to millis so stored value matches query (DB may drop nanos)
    Instant nowMs = now.truncatedTo(ChronoUnit.MILLIS);
    Instant recentCutoff = recentCutoff(now, skipRecent);
    // Phase 1: UPDATE with subquery (H2-compatible default)
    String claimSql = "UPDATE " + tableName() + " SET locked_by=?, locked_at=? " +
        "WHERE event_id IN (" +
        "SELECT event_id FROM " + tableName() +
        " WHERE status IN " + PENDING_STATUS_IN + " AND available_at <= ?" +
        " AND (locked_by IS NULL OR locked_at < ?)" +
        " AND created_at <= ? ORDER BY created_at LIMIT ?)";
    int updated = JdbcTemplate.update(conn, claimSql,
        ownerId, Timestamp.from(nowMs), Timestamp.from(now),
        Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
    if (updated == 0) return List.of();
    // Phase 2: SELECT rows claimed in this cycle
    return selectClaimed(conn, ownerId, nowMs);
  }

  /**
   * Selects rows previously claimed by the given owner at the given lock timestamp.
   * Shared by subclasses that use a two-phase claim (UPDATE then SELECT).
   */
  protected List<OutboxEvent> selectClaimed(Connection conn, String ownerId, Instant lockedAt) {
    String sql = "SELECT event_id, event_type, aggregate_type, aggregate_id, " +
        "tenant_id, payload, headers, attempts, created_at " +
        "FROM " + tableName() + " WHERE locked_by=? AND locked_at=? ORDER BY created_at";
    return JdbcTemplate.query(conn, sql, EVENT_ROW_MAPPER, ownerId, Timestamp.from(lockedAt));
  }

  protected Instant recentCutoff(Instant now, Duration skipRecent) {
    return skipRecent == null ? now : now.minus(skipRecent);
  }

  private static String truncateError(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH - 3) + "...";
  }
}
