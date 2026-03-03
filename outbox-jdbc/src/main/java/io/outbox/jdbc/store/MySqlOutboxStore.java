package io.outbox.jdbc.store;

import io.outbox.jdbc.JdbcTemplate;
import io.outbox.model.OutboxEvent;
import io.outbox.util.JsonCodec;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * MySQL outbox store. Also compatible with TiDB 3.0+.
 *
 * <p>Uses {@code SELECT ... FOR UPDATE SKIP LOCKED} for atomic row-level
 * claim locking, followed by an {@code UPDATE} to mark claimed rows.
 * Safe for multi-instance deployments. Requires MySQL 8.0+ (or TiDB 3.0+).
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
        // Phase 1: SELECT with FOR UPDATE SKIP LOCKED to exclusively lock rows (MySQL 8.0+)
        String lockSql = "SELECT event_id, event_type, aggregate_type, aggregate_id, " +
                "tenant_id, payload, headers, attempts, created_at, available_at FROM " + tableName() +
                " WHERE status IN " + PENDING_STATUS_IN + " AND available_at <= ?" +
                " AND (locked_by IS NULL OR locked_at < ?)" +
                " AND created_at <= ? ORDER BY created_at, event_id LIMIT ?" +
                " FOR UPDATE SKIP LOCKED";
        List<OutboxEvent> events = JdbcTemplate.query(conn, lockSql, EVENT_ROW_MAPPER,
                Timestamp.from(now), Timestamp.from(lockExpiry),
                Timestamp.from(recentCutoff), limit);
        if (events.isEmpty()) return List.of();
        // Phase 2: UPDATE the locked rows (chunked to stay within parameter limits)
        Timestamp lockedAt = Timestamp.from(nowMs);
        for (int start = 0; start < events.size(); start += MAX_BATCH_ROWS) {
            int end = Math.min(start + MAX_BATCH_ROWS, events.size());
            int chunkSize = end - start;
            String placeholders = String.join(",", Collections.nCopies(chunkSize, "?"));
            String updateSql = "UPDATE " + tableName() + " SET locked_by=?, locked_at=? " +
                    "WHERE event_id IN (" + placeholders + ")";
            Object[] params = new Object[2 + chunkSize];
            params[0] = ownerId;
            params[1] = lockedAt;
            for (int i = 0; i < chunkSize; i++) {
                params[2 + i] = events.get(start + i).eventId();
            }
            JdbcTemplate.update(conn, updateSql, params);
        }
        return events;
    }
}
