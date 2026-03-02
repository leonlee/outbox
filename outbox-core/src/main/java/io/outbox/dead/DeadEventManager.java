package io.outbox.dead;

import io.outbox.model.OutboxEvent;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.OutboxStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Convenience facade for querying, counting, and replaying DEAD events.
 *
 * <p>Manages connection lifecycle internally using a {@link ConnectionProvider},
 * similar to how {@link io.outbox.purge.OutboxPurgeScheduler} wraps {@link io.outbox.spi.EventPurger}.
 *
 * @see OutboxStore#queryDead
 * @see OutboxStore#replayDead
 * @see OutboxStore#countDead
 */
public final class DeadEventManager {
    private final ConnectionProvider connectionProvider;
    private final OutboxStore outboxStore;

    public DeadEventManager(ConnectionProvider connectionProvider, OutboxStore outboxStore) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
    }

    /**
     * Queries DEAD events with optional filters.
     *
     * @param eventType     optional event type filter ({@code null} for all)
     * @param aggregateType optional aggregate type filter ({@code null} for all)
     * @param limit         maximum number of events to return
     * @return list of dead events, oldest first
     */
    public List<OutboxEvent> query(String eventType, String aggregateType, int limit) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(true);
            return outboxStore.queryDead(conn, eventType, aggregateType, limit);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query dead events", e);
        }
    }

    /**
     * Replays a single DEAD event by resetting it to NEW status.
     *
     * @param eventId the event ID to replay
     * @return {@code true} if the event was replayed, {@code false} if not found or not DEAD
     */
    public boolean replay(String eventId) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(true);
            return outboxStore.replayDead(conn, eventId) > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replay dead event: " + eventId, e);
        }
    }

    /**
     * Replays all DEAD events matching the given filters, processing in batches.
     *
     * @param eventType     optional event type filter ({@code null} for all)
     * @param aggregateType optional aggregate type filter ({@code null} for all)
     * @param batchSize     number of events to process per batch
     * @return total number of events replayed
     */
    public int replayAll(String eventType, String aggregateType, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        int totalReplayed = 0;
        List<OutboxEvent> batch;
        do {
            int batchReplayed = 0;
            try (Connection conn = connectionProvider.getConnection()) {
                conn.setAutoCommit(true);
                batch = outboxStore.queryDead(conn, eventType, aggregateType, batchSize);
                for (OutboxEvent event : batch) {
                    if (outboxStore.replayDead(conn, event.eventId()) > 0) {
                        batchReplayed++;
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to replay dead events batch; replayed "
                        + totalReplayed + " so far", e);
            }
            totalReplayed += batchReplayed;
            if (!batch.isEmpty() && batchReplayed == 0) {
                break; // all replays in this batch failed; stop to prevent infinite loop
            }
        } while (batch.size() >= batchSize);
        return totalReplayed;
    }

    /**
     * Counts DEAD events, optionally filtered by event type.
     *
     * @param eventType optional event type filter ({@code null} for all)
     * @return the number of dead events matching the filter
     */
    public int count(String eventType) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(true);
            return outboxStore.countDead(conn, eventType);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count dead events", e);
        }
    }
}
