package io.outbox.poller;

import io.outbox.EventEnvelope;
import io.outbox.dispatch.QueuedEvent.ClaimLease;

/**
 * Callback for events discovered by the {@link OutboxPoller}. Implementations decide
 * how to process or forward polled events (e.g. enqueue into a dispatcher's cold queue).
 *
 * @see io.outbox.dispatch.DispatcherPollerHandler
 */
@FunctionalInterface
public interface OutboxPollerHandler {

    /**
     * Handles a polled event.
     *
     * @param event    the event envelope reconstructed from the database row
     * @param attempts the number of previous dispatch attempts
     * @return {@code true} if accepted, {@code false} to signal back-pressure (stops the current poll batch)
     */
    boolean handle(EventEnvelope event, int attempts);

    /**
     * Same, but tells the handler which instance holds the claim lease on this row.
     *
     * <p>Only differs from {@link #handle(EventEnvelope, int)} when claim locking is on. A handler
     * that later wants to undo the claim needs this: a queued copy can outlive its lease, and
     * releasing whichever lease is current at that point — another instance's, or a newer one of
     * this instance's — would hand the event out twice.
     *
     * <p>Defaults to ignoring the owner so existing handlers keep working.
     *
     * @param event      the claimed event
     * @param attempts   delivery attempts so far
     * @param claimLease the lease this row was claimed under, or {@code null} without claim locking
     * @return whether the handler accepted the event
     */
    default boolean handle(EventEnvelope event, int attempts, ClaimLease claimLease) {
        return handle(event, attempts);
    }

    /**
     * Returns the number of events this handler can accept right now.
     * Used by the poller to cap claim size and avoid over-locking rows
     * that cannot be immediately processed. The poller skips the poll
     * cycle entirely when this returns {@code 0}.
     *
     * <p>Default returns {@link Integer#MAX_VALUE} (no cap).
     */
    default int availableCapacity() {
        return Integer.MAX_VALUE;
    }
}
