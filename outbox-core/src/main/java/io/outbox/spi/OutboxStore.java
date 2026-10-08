package io.outbox.spi;

import io.outbox.EventEnvelope;
import io.outbox.model.OutboxEvent;

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
 * @see io.outbox.jdbc.store.AbstractJdbcOutboxStore
 */
public interface OutboxStore {

    /**
     * How stale an event may be and still travel the hot path with a writer stamp.
     *
     * <p>One threshold, two coupled decisions. A store that stamps writer ownership writes
     * {@code locked_at = created_at = occurredAt}; for an event already older than this, that lease
     * reads as expired to every poller the moment it becomes visible — a reservation that reserves
     * nothing. So the store inserts such rows unowned. But an unowned row is claimable by any node
     * at once, so the writer hook must not race a hot copy against those claims: it skips hot
     * delivery for the same events, and the poller — whose claim is exclusive — delivers them.
     * A backfill has already lost any latency the hot path could offer.
     *
     * <p>The hook checks at afterCommit and the store at insert, and insert-age &le; commit-age,
     * so a hot copy always rides with a stamp; the unprotected combination cannot occur.
     *
     * <p>Must stay well below the claim lock-timeout (default PT5M): a stamp aged anywhere inside
     * this window is only a reservation if the timeout has not already passed it. The Spring
     * starter refuses {@code stamp-writer-owner} with a lock-timeout under twice this value;
     * anyone wiring the store directly owes the same check. A stamp on a fresh event still expires
     * if its transaction takes longer than the lock-timeout to commit, and that residual — a
     * multi-minute write transaction racing another node's claim — is the at-least-once tail this
     * design accepts rather than closes.
     */
    Duration WRITER_STAMP_MAX_AGE = Duration.ofSeconds(30);

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
     * <p>This is used when a handler returns {@link io.outbox.DispatchResult.RetryAfter}
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
     * Whether {@link #claimPending} really claims, rather than falling through to an unlocked read.
     *
     * <p>The default {@code claimPending} delegates to {@link #pollPending}, which is a reasonable
     * convenience — most callers only want delivery — but it means "claim locking is configured"
     * and "rows are actually locked" are different statements. Anything whose correctness rests on
     * exclusivity has to ask this one, not the presence of an owner id.
     *
     * <p>Defaults to {@code false}: a store that has not said it locks must not be assumed to.
     *
     * @return {@code true} if {@code claimPending} takes an exclusive lease
     */
    default boolean supportsClaimLocking() {
        return false;
    }

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
     * Clears the claim lease on an event that is still awaiting delivery, leaving its status alone.
     *
     * <p>Used when a dispatch is abandoned before it runs — the poller has already stamped
     * {@code locked_by}, and without this the row sits unclaimable until the lock timeout even
     * though nothing is working on it.
     *
     * <p>Implementations MUST honour BOTH guards:
     * <ul>
     *   <li>only rows in a pending state, so releasing an event that did complete is a no-op
     *       rather than a resurrection;
     *   <li>only rows still leased by {@code claimOwner}. A queued copy can outlive its lease —
     *       the timeout expires, another instance claims the row and starts delivering it — and
     *       clearing that newer lease would hand the event out twice, which is precisely the
     *       duplicate this machinery exists to stop.
     * </ul>
     *
     * <p>Defaults to doing nothing, which is safe but leaves the lease in place; a store that
     * supports claim locking should override it.
     *
     * @param conn       the connection to use
     * @param eventId    the event whose lease should be dropped
     * @param claimOwner the instance that took the lease being released
     * @return number of rows affected
     */
    default int releaseClaim(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
        return 0;
    }

    /**
     * The value {@link #claimPending} will write to {@code locked_at} for a claim taken at
     * {@code now}.
     *
     * <p>Exists so the lease timestamp has exactly one definition. A caller that wants to undo its
     * own claim later has to name the lease it took, and deriving that by re-implementing the
     * store's rounding somewhere else is how the two drift apart — at which point the release
     * silently matches nothing, or worse, matches a lease it does not own.
     *
     * @param now the instant the claim is being taken at
     * @return the timestamp that claim will carry
     */
    default Instant leaseTimestamp(Instant now) {
        return now;
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
