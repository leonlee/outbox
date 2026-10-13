package io.outbox.spi;

import io.outbox.EventEnvelope;
import io.outbox.model.OutboxEvent;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
     * this window is only a reservation if the timeout has not already passed it.
     * With the hot path on, {@code Outbox.multiNode()} refuses a stamping store with a
     * lock-timeout under twice this value; anyone wiring the store directly owes the same check. A stamp on a fresh event still expires
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
     * {@link #markDone(Connection, String)} under claim fencing: applied only while this write
     * still has authority over the row.
     *
     * <p>A dispatch can outlive its lease: the listener runs past the lock timeout, and another
     * claim takes the row over. Unfenced, the late write would clear that newer claim (a RETRY
     * makes the row claimable by a third copy while the second is still running) or overwrite
     * the newer holder's outcome. The four status writes that take a {@code claimOwner} share
     * these modes:
     * <ul>
     *   <li>{@code claimOwner == null}: unfenced, exactly the plain write.
     *   <li>{@code claimedAt != null}: the exact lease. Applies only while {@code locked_by} and
     *       {@code locked_at} still equal {@code claimOwner} and {@code claimedAt}, the
     *       {@link #leaseTimestamp} value the claim wrote. A newer claim, even by the same owner,
     *       or a newer holder's nonterminal write that cleared it, makes this a no-op.
     *   <li>{@code claimedAt == null}: a hot copy, which was never claimed. A store that stamps
     *       its writer owner applies the write only while the row still carries that stamp
     *       ({@code locked_at = created_at}); losing the stamp to a claim loses write authority.
     *       A store without stamping applies it only while the row is unclaimed. Nothing in such
     *       a row records a claim that has since been released, so that last case is not fenced
     *       against it.
     * </ul>
     *
     * <p>The defaults delegate to the plain write when {@code claimOwner} is {@code null}, or
     * when this store does not {@link #supportsClaimLocking() lock} (nothing to fence against).
     * A locking store that has not implemented fencing throws
     * {@link UnsupportedOperationException} for a fenced write rather than silently ignoring the
     * fence; see {@link #supportsClaimFencing()}.
     *
     * @param conn       the JDBC connection
     * @param eventId    the event ID to update
     * @param claimOwner the owner this write runs under, or {@code null} for an unfenced write
     * @param claimedAt  the exact lease timestamp, or {@code null} for a hot copy
     * @return the number of rows updated: 0 if the event is already terminal or the write no
     *         longer has authority over it
     */
    default int markDone(Connection conn, String eventId, String claimOwner, Instant claimedAt) {
        requireFencingIfLocking(claimOwner);
        return markDone(conn, eventId);
    }

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
     * {@link #markRetry(Connection, String, Instant, String)} under claim fencing; see
     * {@link #markDone(Connection, String, String, Instant)}.
     *
     * @return the number of rows updated (0 if terminal or no longer under this write's authority)
     */
    default int markRetry(Connection conn, String eventId, Instant nextAt, String error,
                          String claimOwner, Instant claimedAt) {
        requireFencingIfLocking(claimOwner);
        return markRetry(conn, eventId, nextAt, error);
    }

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
     * {@link #markDead(Connection, String, String)} under claim fencing; see
     * {@link #markDone(Connection, String, String, Instant)}.
     *
     * @return the number of rows updated (0 if terminal or no longer under this write's authority)
     */
    default int markDead(Connection conn, String eventId, String error, String claimOwner, Instant claimedAt) {
        requireFencingIfLocking(claimOwner);
        return markDead(conn, eventId, error);
    }

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
     * {@link #markDeferred(Connection, String, Instant)} under claim fencing; see
     * {@link #markDone(Connection, String, String, Instant)}.
     *
     * @return the number of rows updated (0 if terminal or no longer under this write's authority)
     */
    default int markDeferred(Connection conn, String eventId, Instant nextAt, String claimOwner, Instant claimedAt) {
        requireFencingIfLocking(claimOwner);
        return markDeferred(conn, eventId, nextAt);
    }

    /**
     * Whether this store implements the claim-fenced status writes
     * ({@link #markDone(Connection, String, String, Instant)} and the other three).
     *
     * <p>Defaults to {@code false}. Builders that would issue fenced writes against a store that
     * {@link #supportsClaimLocking() locks} reject one that returns {@code false}, so an existing
     * custom store fails at startup instead of losing the fence silently.
     *
     * @return {@code true} if the fenced overloads honour their fence
     */
    default boolean supportsClaimFencing() {
        return false;
    }

    /**
     * The owner this store stamps into {@code locked_by} at insert, if it stamps at all.
     *
     * <p>A stamp is the hot copy's lease: {@code (owner, created_at)}. Without one a hot copy has
     * nothing to fence on but an unclaimed row, so {@code Outbox.multiNode()} with the hot path
     * requires it from a store that {@link #supportsClaimLocking() locks}, and requires it to equal
     * the claim owner.
     *
     * <p>Defaults to empty: the store writes rows unowned.
     *
     * @return the stamped owner, or empty if rows are written unowned
     */
    default Optional<String> writerOwnerId() {
        return Optional.empty();
    }

    private void requireFencingIfLocking(String claimOwner) {
        if (claimOwner != null && supportsClaimLocking()) {
            throw new UnsupportedOperationException(getClass().getName()
                    + " supports claim locking but does not implement the claim-fenced status writes;"
                    + " override them and supportsClaimFencing()");
        }
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
