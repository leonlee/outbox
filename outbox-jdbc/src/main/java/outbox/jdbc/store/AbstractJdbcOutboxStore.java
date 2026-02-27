package outbox.jdbc.store;

import outbox.EventEnvelope;
import outbox.jdbc.JdbcTemplate;
import outbox.jdbc.TableNames;
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
    protected static final String DEFAULT_TABLE = TableNames.DEFAULT_TABLE;
    private static final int MAX_ERROR_LENGTH = 4000;
    private static final int MAX_BATCH_ROWS = 500;

    protected static final String PENDING_STATUS_IN =
            "(" + EventStatus.NEW.code() + "," + EventStatus.RETRY.code() + ")";

    protected static final String TERMINAL_STATUS_IN =
            "(" + EventStatus.DONE.code() + "," + EventStatus.DEAD.code() + ")";

    protected static final JdbcTemplate.RowMapper<OutboxEvent> EVENT_ROW_MAPPER = rs -> {
        Timestamp availTs = rs.getTimestamp("available_at");
        return new OutboxEvent(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("tenant_id"),
                rs.getString("payload"),
                rs.getString("headers"),
                rs.getInt("attempts"),
                Objects.requireNonNull(rs.getTimestamp("created_at"), "created_at is null").toInstant(),
                availTs != null ? availTs.toInstant() : null);
    };

    private final String tableName;
    private final JsonCodec jsonCodec;

    protected AbstractJdbcOutboxStore() {
        this(DEFAULT_TABLE);
    }

    protected AbstractJdbcOutboxStore(String tableName) {
        this(tableName, JsonCodec.getDefault());
    }

    protected AbstractJdbcOutboxStore(String tableName, JsonCodec jsonCodec) {
        this.tableName = TableNames.validate(tableName);
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    /**
     * Creates a new instance of the same store type with the given {@link JsonCodec}.
     *
     * <p>Used by {@link JdbcOutboxStores#detect(String, JsonCodec)} to create codec-customised
     * instances without hard-coding subclass types.
     *
     * @param jsonCodec the JSON codec to use
     * @return a new outbox store instance configured with the given codec
     */
    public abstract AbstractJdbcOutboxStore withJsonCodec(JsonCodec jsonCodec);

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

    protected JsonCodec jsonCodec() {
        return jsonCodec;
    }

    /**
     * Returns the SQL placeholder expression for JSON/JSONB columns.
     *
     * <p>Defaults to {@code "?"} which works for H2 (CLOB) and MySQL (JSON).
     * PostgreSQL overrides with {@code "CAST(? AS jsonb)"} because the JDBC driver
     * rejects implicit VARCHAR-to-JSONB coercion.
     */
    protected String jsonPlaceholder() {
        return "?";
    }

    @Override
    public void insertNew(Connection conn, EventEnvelope event) {
        String jp = jsonPlaceholder();
        String sql = "INSERT INTO " + tableName() + " (" +
                "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
                "payload, headers, status, attempts, available_at, created_at, done_at, last_error, " +
                "locked_by, locked_at" +
                ") VALUES (?,?,?,?,?," + jp + "," + jp + ",?,?,?,?,NULL,NULL,NULL,NULL)";
        Timestamp now = Timestamp.from(event.occurredAt());
        Timestamp availableAt = event.availableAt() != null
                ? Timestamp.from(event.availableAt()) : now;
        JdbcTemplate.update(conn, sql,
                event.eventId(), event.eventType(), event.aggregateType(),
                event.aggregateId(), event.tenantId(), event.payloadJson(),
                jsonCodec.toJson(event.headers()),
                EventStatus.NEW.code(), 0, availableAt, now);
    }

    @Override
    public void insertBatch(Connection conn, List<EventEnvelope> events) {
        if (events.size() <= 1) {
            for (EventEnvelope event : events) {
                insertNew(conn, event);
            }
            return;
        }
        // Chunk large batches to stay within database statement size limits
        if (events.size() > MAX_BATCH_ROWS) {
            for (int start = 0; start < events.size(); start += MAX_BATCH_ROWS) {
                int end = Math.min(start + MAX_BATCH_ROWS, events.size());
                insertBatchChunk(conn, events.subList(start, end));
            }
        } else {
            insertBatchChunk(conn, events);
        }
    }

    private void insertBatchChunk(Connection conn, List<EventEnvelope> events) {
        // Pure SQL multi-row INSERT: VALUES (...), (...), ...
        String jp = jsonPlaceholder();
        String row = "(?,?,?,?,?," + jp + "," + jp + ",?,?,?,?,NULL,NULL,NULL,NULL)";
        StringBuilder sql = new StringBuilder("INSERT INTO " + tableName() + " (" +
                "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
                "payload, headers, status, attempts, available_at, created_at, done_at, last_error, " +
                "locked_by, locked_at) VALUES ");
        sql.append(row);
        for (int i = 1; i < events.size(); i++) {
            sql.append(',').append(row);
        }
        Object[] params = new Object[events.size() * 11];
        int idx = 0;
        for (EventEnvelope event : events) {
            Timestamp now = Timestamp.from(event.occurredAt());
            Timestamp availableAt = event.availableAt() != null
                    ? Timestamp.from(event.availableAt()) : now;
            params[idx++] = event.eventId();
            params[idx++] = event.eventType();
            params[idx++] = event.aggregateType();
            params[idx++] = event.aggregateId();
            params[idx++] = event.tenantId();
            params[idx++] = event.payloadJson();
            params[idx++] = jsonCodec.toJson(event.headers());
            params[idx++] = EventStatus.NEW.code();
            params[idx++] = 0;
            params[idx++] = availableAt;
            params[idx++] = now;
        }
        JdbcTemplate.update(conn, sql.toString(), params);
    }

    @Override
    public int markDone(Connection conn, String eventId) {
        String sql = "UPDATE " + tableName() +
                " SET status=" + EventStatus.DONE.code() + ", done_at=?, locked_by=NULL, locked_at=NULL" +
                " WHERE event_id=? AND status NOT IN " + TERMINAL_STATUS_IN;
        return JdbcTemplate.update(conn, sql, Timestamp.from(Instant.now()), eventId);
    }

    @Override
    public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
        String sql = "UPDATE " + tableName() +
                " SET status=" + EventStatus.RETRY.code() +
                ", attempts=attempts+1, available_at=?, last_error=?, locked_by=NULL, locked_at=NULL" +
                " WHERE event_id=? AND status NOT IN " + TERMINAL_STATUS_IN;
        return JdbcTemplate.update(conn, sql, Timestamp.from(nextAt), truncateError(error), eventId);
    }

    @Override
    public int markDead(Connection conn, String eventId, String error) {
        String sql = "UPDATE " + tableName() +
                " SET status=" + EventStatus.DEAD.code() + ", done_at=?, last_error=?, locked_by=NULL, locked_at=NULL" +
                " WHERE event_id=? AND status NOT IN " + TERMINAL_STATUS_IN;
        return JdbcTemplate.update(conn, sql, Timestamp.from(Instant.now()), truncateError(error), eventId);
    }

    @Override
    public int markDeferred(Connection conn, String eventId, Instant nextAt) {
        String sql = "UPDATE " + tableName() +
                " SET status=" + EventStatus.RETRY.code() +
                ", available_at=?, locked_by=NULL, locked_at=NULL" +
                " WHERE event_id=? AND status NOT IN " + TERMINAL_STATUS_IN;
        return JdbcTemplate.update(conn, sql, Timestamp.from(nextAt), eventId);
    }

    @Override
    public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
        String sql = "SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
                "payload, headers, attempts, created_at, available_at " +
                "FROM " + tableName() + " WHERE status IN " + PENDING_STATUS_IN +
                " AND available_at <= ? AND created_at <= ? " +
                "ORDER BY created_at LIMIT ?";
        Instant recentCutoff = recentCutoff(now, skipRecent);
        return JdbcTemplate.query(conn, sql, EVENT_ROW_MAPPER,
                Timestamp.from(now), Timestamp.from(recentCutoff), limit);
    }

    /**
     * H2-compatible two-phase claim: UPDATE with subquery, then SELECT claimed rows.
     *
     * <p><strong>Not atomic under concurrent access.</strong> Two concurrent pollers may
     * claim overlapping rows because H2 does not support {@code FOR UPDATE SKIP LOCKED}.
     * This default is intended for testing and single-instance deployments only.
     * Production multi-instance deployments should use {@code PostgresOutboxStore}
     * ({@code FOR UPDATE SKIP LOCKED}) or {@code MySqlOutboxStore}
     * ({@code UPDATE ... ORDER BY ... LIMIT}).
     */
    @Override
    public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                          Instant lockExpiry, Duration skipRecent, int limit) {
        Objects.requireNonNull(ownerId, "ownerId");
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
                "tenant_id, payload, headers, attempts, created_at, available_at " +
                "FROM " + tableName() + " WHERE locked_by=? AND locked_at=? ORDER BY created_at";
        return JdbcTemplate.query(conn, sql, EVENT_ROW_MAPPER, ownerId, Timestamp.from(lockedAt));
    }

    @Override
    public List<OutboxEvent> queryDead(Connection conn, String eventType, String aggregateType, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
                        "payload, headers, attempts, created_at, available_at FROM " + tableName() +
                        " WHERE status=" + EventStatus.DEAD.code());
        List<Object> params = new java.util.ArrayList<>();
        if (eventType != null) {
            sql.append(" AND event_type=?");
            params.add(eventType);
        }
        if (aggregateType != null) {
            sql.append(" AND aggregate_type=?");
            params.add(aggregateType);
        }
        sql.append(" ORDER BY created_at LIMIT ?");
        params.add(limit);
        return JdbcTemplate.query(conn, sql.toString(), EVENT_ROW_MAPPER, params.toArray());
    }

    @Override
    public int replayDead(Connection conn, String eventId) {
        String sql = "UPDATE " + tableName() +
                " SET status=" + EventStatus.NEW.code() +
                ", attempts=0, available_at=?, done_at=NULL, last_error=NULL, locked_by=NULL, locked_at=NULL" +
                " WHERE event_id=? AND status=" + EventStatus.DEAD.code();
        return JdbcTemplate.update(conn, sql, Timestamp.from(Instant.now()), eventId);
    }

    @Override
    public int countDead(Connection conn, String eventType) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM " + tableName() + " WHERE status=" + EventStatus.DEAD.code());
        List<Object> params = new java.util.ArrayList<>();
        if (eventType != null) {
            sql.append(" AND event_type=?");
            params.add(eventType);
        }
        List<Integer> result = JdbcTemplate.query(conn, sql.toString(),
                rs -> rs.getInt(1), params.toArray());
        return result.isEmpty() ? 0 : result.get(0);
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
