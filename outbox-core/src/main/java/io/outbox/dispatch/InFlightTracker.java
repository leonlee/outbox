package io.outbox.dispatch;

/**
 * Tracks in-flight events to prevent duplicate concurrent dispatch of the same event.
 *
 * @see DefaultInFlightTracker
 */
public interface InFlightTracker {

    /**
     * Attempts to acquire exclusive processing rights for the given event.
     *
     * @param eventId the event ID to acquire
     * @return {@code true} if acquired, {@code false} if already in flight
     */
    boolean tryAcquire(String eventId);

    /** Returned by {@link #acquire} when the event is already held by someone else. */
    long NOT_ACQUIRED = -1L;

    /**
     * Acquires the event and returns a token identifying <em>this</em> acquisition.
     *
     * <p>{@link #tryAcquire} is not enough once entries can expire. A listener that outruns the
     * TTL has its entry reclaimed and handed to a second worker; when the first one finally
     * returns, an id-keyed {@link #release} would drop the second worker's entry and let a third
     * copy in. The token lets completion say which acquisition it is ending, so a stale one is
     * ignored.
     *
     * <p>Defaults to the untokened behaviour, which is what an implementation without expiry
     * needs anyway.
     *
     * @param eventId the event to acquire
     * @return a token for this acquisition, or {@link #NOT_ACQUIRED}
     */
    default long acquire(String eventId) {
        return tryAcquire(eventId) ? 0L : NOT_ACQUIRED;
    }

    /**
     * Releases only if {@code token} still identifies the current acquisition.
     *
     * @param eventId the event to release
     * @param token   the value {@link #acquire} returned
     */
    default void release(String eventId, long token) {
        release(eventId);
    }

    /**
     * Marks settled only if {@code token} still identifies the current acquisition.
     *
     * @param eventId the settled event
     * @param token   the value {@link #acquire} returned
     */
    default void markSettled(String eventId, long token) {
        markSettled(eventId);
    }

    /**
     * Releases processing rights, allowing the event to be dispatched again.
     *
     * @param eventId the event ID to release
     */
    void release(String eventId);

    /**
     * Keeps the event tracked but records that it has finished rather than that it is running.
     *
     * <p>A failed {@link #tryAcquire} is otherwise ambiguous — "another worker has it right now" and
     * "this already completed a moment ago" look identical — and the two demand opposite handling.
     * A caller may safely undo a claim only in the second case; doing it in the first hands a live
     * event to somebody else.
     *
     * <p>Defaults to {@link #release}, i.e. no suppression, which is the conservative reading: an
     * implementation that has not opted in must not be assumed to distinguish the two states. One
     * that wants replay suppression has to override this and {@link #isSettled} together.
     *
     * @param eventId the event that reached a terminal state
     */
    default void markSettled(String eventId) {
        release(eventId);
    }

    /**
     * Drops the entry only if it is a settled marker, never a running acquisition.
     *
     * <p>For clearing a replay-suppression marker found to be stale. An id-keyed {@link #release}
     * is not safe there: the marker can expire while the caller is busy, the event can be handed
     * out again, and the caller would then end that newer acquisition instead.
     *
     * <p>The default is check-then-act and so not atomic; a tracker that overrides
     * {@link #markSettled} should override this too.
     *
     * @param eventId the event whose settled marker should be dropped
     */
    default void releaseSettled(String eventId) {
        if (isSettled(eventId)) {
            release(eventId);
        }
    }

    /**
     * Whether this id is held because it recently settled, as opposed to being in flight now.
     *
     * @param eventId the event to test
     * @return {@code true} only if the entry is a settled marker
     */
    default boolean isSettled(String eventId) {
        return false;
    }

    /**
     * Whether this id is being delivered right now — held, and not merely a settled marker.
     *
     * <p>Lets a caller refuse to queue a second copy of something already running, instead of
     * queueing it and discovering the clash later at dispatch. The distinction from
     * {@link #isSettled} matters: a settled id may legitimately come round again after a replay,
     * and treating that as "still running" would silently swallow it.
     *
     * <p>Defaults to {@code false} — an implementation that cannot tell must not be assumed to,
     * and the caller falls back to the check at dispatch time.
     *
     * @param eventId the event to test
     * @return {@code true} only if a live, unsettled entry is held
     */
    default boolean isRunning(String eventId) {
        return false;
    }

    /**
     * Whether entries acquired from this tracker are eventually reclaimed without an explicit
     * {@link #release(String)}.
     *
     * <p>{@link OutboxDispatcher} requires this when replay suppression is on, because in that
     * mode a settled event is deliberately never released: with no expiry the entry would live
     * forever and that event could never be dispatched again.
     *
     * <p>Defaults to {@code false} — an implementation that does expire entries must say so.
     *
     * @return {@code true} if entries expire on their own
     */
    default boolean hasTtl() {
        return false;
    }
}
