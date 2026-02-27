package outbox.spi;

import outbox.EventEnvelope;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Persistence contract for outbox events, managing status transitions
 * through the lifecycle: NEW → DONE, NEW → RETRY → DONE, or NEW → DEAD.
 *
 * <p>All methods receive an explicit {@link Connection} so the caller controls
 * transaction boundaries. Implementations live in the {@code outbox-jdbc} module.
 *
 * @see outbox.jdbc.store.AbstractJdbcOutboxStore
 */
public interface OutboxStore {

    /**
     * Inserts a new event with status NEW.
     *
     * @param conn  the JDBC connection (typically within a transaction)
     * @param event the event envelope to persist
     */
    void insertNew(Connection conn, EventEnvelope event);

    /**
     * Inserts multiple events in a batch with status NEW.
     *
     * <p>Default loops {@link #insertNew}. JDBC implementations may override
     * with {@code addBatch}/{@code executeBatch} for better throughput.
     *
     * @param conn   the JDBC connection (typically within a transaction)
     * @param events the event envelopes to persist
     */
    default void insertBatch(Connection conn, List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            insertNew(conn, event);
        }
    }

    /**
     * Marks an event as DONE (successfully processed).
     *
     * @param conn    the JDBC connection
     * @param eventId the event ID to update
     * @return the number of rows updated (0 or 1)
     */
    int markDone(Connection conn, String eventId);

    /**
     * Marks an event for retry with a scheduled next-attempt time.
     *
     * <p>Implementations <strong>must</strong> increment the event's {@code attempts} column
     * as part of this operation. The dispatcher relies on the stored attempt count to
     * determine when {@code maxAttempts} has been reached.
     *
     * @param conn    the JDBC connection
     * @param eventId the event ID to update
     * @param nextAt  earliest time for the next attempt
     * @param error   error message from the failed attempt (may be {@code null})
     * @return the number of rows updated (0 or 1)
     */
    int markRetry(Connection conn, String eventId, Instant nextAt, String error);

    /**
     * Marks an event as DEAD (permanently failed, no more retries).
     *
     * @param conn    the JDBC connection
     * @param eventId the event ID to update
     * @param error   error message describing the failure (may be {@code null})
     * @return the number of rows updated (0 or 1)
     */
    int markDead(Connection conn, String eventId, String error);

    /**
     * Marks an event as deferred (handler requested retry-after) without incrementing
     * the attempt count or recording an error.
     *
     * <p>This is used when a handler returns {@link outbox.DispatchResult.RetryAfter}
     * to reschedule delivery without penalising the event's retry budget.
     *
     * <p>Default implementation falls back to {@link #markRetry} which <em>does</em>
     * increment attempts. JDBC implementations should override this with a proper
     * implementation that preserves the attempt count.
     *
     * @param conn    the JDBC connection
     * @param eventId the event ID to update
     * @param nextAt  earliest time for the next delivery attempt
     * @return the number of rows updated (0 or 1)
     */
    default int markDeferred(Connection conn, String eventId, Instant nextAt) {
        return markRetry(conn, eventId, nextAt, null);
    }

    /**
     * Retrieves pending events eligible for processing (no locking).
     *
     * @param conn       the JDBC connection
     * @param now        current timestamp for evaluating retry delays
     * @param skipRecent duration to skip recently-created events (avoids racing with in-flight hot-path)
     * @param limit      maximum number of events to return
     * @return list of pending events, oldest first
     */
    List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit);

    /**
     * Claims and returns pending events with owner-based locking for multi-instance deployments.
     *
     * <p>Default falls back to {@link #pollPending} (no locking). Database-specific
     * subclasses override this with row-level locking (e.g. {@code FOR UPDATE SKIP LOCKED}).
     *
     * @param conn       the JDBC connection
     * @param ownerId    unique identifier for the claiming poller instance
     * @param now        current timestamp
     * @param lockExpiry timestamp before which existing claims are considered expired
     * @param skipRecent duration to skip recently-created events
     * @param limit      maximum number of events to claim
     * @return list of claimed events
     */
    default List<OutboxEvent> claimPending(
            Connection conn, String ownerId, Instant now,
            Instant lockExpiry, Duration skipRecent, int limit) {
        return pollPending(conn, now, skipRecent, limit);
    }

    /**
     * Queries events in DEAD status with optional filters.
     *
     * @param conn          the JDBC connection
     * @param eventType     optional event type filter ({@code null} for all)
     * @param aggregateType optional aggregate type filter ({@code null} for all)
     * @param limit         maximum number of events to return
     * @return list of dead events, oldest first
     */
    default List<OutboxEvent> queryDead(Connection conn, String eventType, String aggregateType, int limit) {
        return List.of();
    }

    /**
     * Replays a DEAD event by resetting it to NEW status with zero attempts.
     *
     * <p>Only events currently in DEAD status are affected. Returns 0 if the event
     * does not exist or is not DEAD (idempotent).
     *
     * @param conn    the JDBC connection
     * @param eventId the event ID to replay
     * @return the number of rows updated (0 or 1)
     */
    default int replayDead(Connection conn, String eventId) {
        return 0;
    }

    /**
     * Counts events in DEAD status, optionally filtered by event type.
     *
     * @param conn      the JDBC connection
     * @param eventType optional event type filter ({@code null} for all)
     * @return the number of dead events matching the filter
     */
    default int countDead(Connection conn, String eventType) {
        return 0;
    }
}
