package io.outbox.jdbc.store;

import io.outbox.EventEnvelope;
import io.outbox.jdbc.JdbcTemplate;
import io.outbox.jdbc.TableNames;
import io.outbox.model.EventStatus;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.JsonCodec;
import io.outbox.spi.OutboxStore;

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
 * {@code META-INF/services/io.outbox.jdbc.store.AbstractJdbcOutboxStore}.
 *
 * @see JdbcOutboxStores
 */
public abstract class AbstractJdbcOutboxStore implements OutboxStore {
    protected static final String DEFAULT_TABLE = TableNames.DEFAULT_TABLE;
    protected static final int MAX_BATCH_ROWS = 500;
    protected static final String PENDING_STATUS_IN =
            "(" + EventStatus.NEW.code() + "," + EventStatus.RETRY.code() + ")";
    protected static final String TERMINAL_STATUS_IN =
            "(" + EventStatus.DONE.code() + "," + EventStatus.DEAD.code() + ")";

    private static final int MAX_ERROR_LENGTH = 4000;

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

    /**
     * The owner stamped on rows this instance writes, or {@code null} to write them unowned.
     *
     * <p>Rationale: the hot path enqueues in memory and never touches {@code locked_by}, so
     * a row stays {@code PENDING / locked_by = NULL} while its hot copy is queued. Any other
     * instance's poller may claim it in that window and deliver a second copy — and the in-memory
     * guards cannot see across JVMs. Stamping the writer at INSERT means the row is already owned
     * the moment it becomes visible to anyone else, so hot and cold copies of the same event
     * always land in the same process, where the tracker can see both.
     *
     * <p>It also covers a case timing-based guards cannot: {@code created_at} is set inside the
     * business transaction but the hot copy only starts at afterCommit, so for a transaction
     * longer than {@code skip-recent} the row is already claimable the instant it is visible.
     * Ownership does not depend on how long the transaction took.
     *
     * <p>The stamp sets {@code locked_at} equal to {@code created_at}, and that equality is what
     * {@code claimPending} keys off to tell an <b>unclaimed writer stamp</b> from an <b>active
     * poller lease</b>. The distinction is load-bearing in both directions:
     *
     * <ul>
     *   <li>the writing instance must be able to claim its own rows <em>once</em>, immediately —
     *       many writes never enter the hot queue (delayed events, a full queue), and making those wait out the lock timeout
     *       would turn a one-second latency into five minutes;
     *   <li>but only once. A standing {@code locked_by = <self>} exemption would let every
     *       subsequent poll re-claim the same still-PENDING row and enqueue another copy of it,
     *       filling the cold queue with duplicates and starving newer events. A poller claim
     *       overwrites {@code locked_at} with the current time, so the equality no longer holds
     *       and the lease applies normally from then on.
     * </ul>
     *
     * <p>Takeover is unaffected: once {@code locked_at} is older than the lock timeout, any
     * instance may claim the row, so a stamp left behind by a dead pod is not stranded.
     */
    private final String writerOwnerId;

    /**
     * @see OutboxStore#WRITER_STAMP_MAX_AGE — shared with the writer hook, which skips hot
     * delivery for the same events so an unstamped row is never raced by a hot copy.
     */
    private static final Duration WRITER_STAMP_MAX_AGE = OutboxStore.WRITER_STAMP_MAX_AGE;

    protected AbstractJdbcOutboxStore() {
        this(DEFAULT_TABLE, null);
    }

    protected AbstractJdbcOutboxStore(String tableName) {
        this(tableName, null);
    }

    /**
     * @param writerOwnerId stamped into {@code locked_by} at INSERT so that only this instance's
     *                      poller may claim the rows it wrote — see the class notes on writer ownership.
     *                      {@code null} keeps the previous behaviour (rows are written unowned).
     */
    protected AbstractJdbcOutboxStore(String tableName, String writerOwnerId) {
        this.tableName = TableNames.validate(tableName);
        this.writerOwnerId = writerOwnerId;
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

    protected JsonCodec jsonCodec() {
        return JsonCodec.getDefault();
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

    /**
     * The owner to reserve this row for, or {@code null} to insert it unowned.
     *
     * @param occurredAt the event's timestamp, which also becomes {@code created_at}
     */
    private String stampFor(Instant occurredAt) {
        if (writerOwnerId == null) {
            return null;
        }
        return occurredAt.isBefore(Instant.now().minus(WRITER_STAMP_MAX_AGE)) ? null : writerOwnerId;
    }

    @Override
    public void insertNew(Connection conn, EventEnvelope event) {
        String jp = jsonPlaceholder();
        String sql = "INSERT INTO " + tableName() + " (" +
                "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
                "payload, headers, status, attempts, available_at, created_at, done_at, last_error, " +
                "locked_by, locked_at" +
                ") VALUES (?,?,?,?,?," + jp + "," + jp + ",?,?,?,?,NULL,NULL,?,?)";
        Timestamp now = Timestamp.from(event.occurredAt());
        Timestamp availableAt = event.availableAt() != null
                ? Timestamp.from(event.availableAt()) : now;
        // Decided once. Called twice, the two calls read the clock separately, and an event sitting
        // exactly on the staleness boundary could answer differently each time — writing locked_by
        // with a NULL locked_at. No branch of the claim predicate matches that row (NULL is neither
        // equal to created_at nor less than lockExpiry), so it would never be delivered by anyone.
        String stamp = stampFor(event.occurredAt());
        JdbcTemplate.update(conn, sql,
                event.eventId(), event.eventType(), event.aggregateType(),
                event.aggregateId(), event.tenantId(), event.payloadJson(),
                event.headers().isEmpty() ? null : jsonCodec().toJson(event.headers()),
                EventStatus.NEW.code(), 0, availableAt, now,
                stamp, stamp == null ? null : now);
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
        String row = "(?,?,?,?,?," + jp + "," + jp + ",?,?,?,?,NULL,NULL,?,?)";
        StringBuilder sql = new StringBuilder("INSERT INTO " + tableName() + " (" +
                "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
                "payload, headers, status, attempts, available_at, created_at, done_at, last_error, " +
                "locked_by, locked_at) VALUES ");
        sql.append(row);
        for (int i = 1; i < events.size(); i++) {
            sql.append(',').append(row);
        }
        Object[] params = new Object[events.size() * 13];
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
            params[idx++] = event.headers().isEmpty() ? null : jsonCodec().toJson(event.headers());
            params[idx++] = EventStatus.NEW.code();
            params[idx++] = 0;
            params[idx++] = availableAt;
            params[idx++] = now;
            String stamp = stampFor(event.occurredAt());
            params[idx++] = stamp;
            params[idx++] = stamp == null ? null : now;
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
                " SET status=" + EventStatus.NEW.code() +
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
                "ORDER BY created_at, event_id LIMIT ?";
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
     * or {@code MySqlOutboxStore} (both use {@code FOR UPDATE SKIP LOCKED}).
     *
     * <p>Which is why {@link #supportsClaimLocking()} is left at the SPI's {@code false} here — a
     * claim that two pollers can win is not the exclusivity poller-only delivery depends on. The
     * two stores that do use {@code SKIP LOCKED} opt in for themselves.
     */
    @Override
    public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                          Instant lockExpiry, Duration skipRecent, int limit) {
        Objects.requireNonNull(ownerId, "ownerId");
        // The lease this claim writes; see leaseTimestamp() for why it is truncated and offset
        Instant nowMs = leaseTimestamp(now);
        Instant recentCutoff = recentCutoff(now, skipRecent);
        // Phase 1: UPDATE with subquery (H2-compatible default)
        String claimSql = "UPDATE " + tableName() + " SET locked_by=?, locked_at=? " +
                "WHERE event_id IN (" +
                "SELECT event_id FROM " + tableName() +
                " WHERE status IN " + PENDING_STATUS_IN + " AND available_at <= ?" +
                " AND (locked_by IS NULL"
                + " OR (locked_by = ? AND locked_at = created_at)"
                + " OR locked_at < ?)" +
                " AND created_at <= ? ORDER BY created_at, event_id LIMIT ?)";
        int updated = JdbcTemplate.update(conn, claimSql,
                ownerId, Timestamp.from(nowMs), Timestamp.from(now),
                ownerId, Timestamp.from(lockExpiry), Timestamp.from(recentCutoff), limit);
        if (updated == 0) {
            return List.of();
        }
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
                "FROM " + tableName() + " WHERE locked_by=? AND locked_at=? ORDER BY created_at, event_id";
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
        sql.append(" ORDER BY created_at, event_id LIMIT ?");
        params.add(limit);
        return JdbcTemplate.query(conn, sql.toString(), EVENT_ROW_MAPPER, params.toArray());
    }

    @Override
    public int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
        if (claimOwner == null || claimedAt == null) {
            return 0;
        }
        // All three predicates are load-bearing. status stops a completed row being resurrected.
        // locked_by stops us clearing a lease another instance has taken over. locked_at stops us
        // clearing a NEWER lease of our OWN: an owner id is reused for the life of the pod, so
        // after a lease expires and the same pod re-claims the row, a copy queued under the old
        // lease would otherwise release the new one out from under the copy riding it.
        String sql = "UPDATE " + tableName() +
                " SET locked_by=NULL, locked_at=NULL" +
                " WHERE event_id=? AND locked_by=? AND locked_at=? AND status IN " + PENDING_STATUS_IN;
        return JdbcTemplate.update(conn, sql, eventId, claimOwner, Timestamp.from(claimedAt));
    }

    @Override
    public Instant leaseTimestamp(Instant now) {
        // Truncated so the stored value round-trips: the column is millisecond-precision and a
        // nanosecond-precision Instant would never compare equal to what came back out of it.
        //
        // Plus one millisecond so a poller lease can never equal created_at. The writer stamp is
        // recognised by locked_at = created_at alone; a claim landing in the same millisecond as a
        // millisecond-aligned created_at would otherwise read as an unclaimed stamp, and its owner
        // would re-claim the row on the next poll while this lease is still valid. A row is only
        // claimable once created_at <= now, so now + 1ms is strictly after every claimable row.
        return now.truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
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
