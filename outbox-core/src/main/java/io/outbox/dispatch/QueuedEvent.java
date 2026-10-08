package io.outbox.dispatch;

import io.outbox.EventEnvelope;

import java.util.Objects;

/**
 * Internal wrapper pairing an {@link EventEnvelope} with its origin queue
 * and attempt count. Used by {@link OutboxDispatcher} to track events through the
 * dual-queue processing pipeline.
 *
 * <p>{@code enqueuedAtNanos} is a {@link System#nanoTime()} reading taken when the wrapper is
 * created. {@link OutboxDispatcher} reads it off the head of the hot queue to decide whether the
 * hot path is still delivering fast enough to be worth using — see
 * {@link OutboxDispatcher.Builder#hotTripMs(long)}. It is a monotonic duration source, not a
 * wall-clock instant, so it must never be compared against {@code created_at} or persisted.
 */
public record QueuedEvent(EventEnvelope envelope, Source source, int attempts, long enqueuedAtNanos,
                          ClaimLease claimLease) {

    /**
     * The poller lease a cold copy was created under: which instance took it, and when.
     *
     * <p>Both halves are needed to undo a claim safely. The owner alone is not enough because an
     * owner id lives as long as the pod does — after a lease expires and the same pod re-claims the
     * row, a copy queued under the old lease would match the new one.
     *
     * @param owner     the instance holding the lease
     * @param claimedAt the value written to {@code locked_at}, from
     *                  {@code OutboxStore#leaseTimestamp}
     */
    public record ClaimLease(String owner, java.time.Instant claimedAt) {
    }

    /** Stamps {@code enqueuedAtNanos} with the current {@link System#nanoTime()}. */
    public QueuedEvent(EventEnvelope envelope, Source source, int attempts) {
        this(envelope, source, attempts, System.nanoTime(), null);
    }

    public QueuedEvent(EventEnvelope envelope, Source source, int attempts, long enqueuedAtNanos) {
        this(envelope, source, attempts, enqueuedAtNanos, null);
    }

    /**
     * Indicates whether an event arrived via the hot path or cold (poller) path.
     */
    public enum Source {
        /**
         * Event enqueued directly via after-commit hook.
         */
        HOT,
        /**
         * Event enqueued by the poller from the database.
         */
        COLD
    }

    public QueuedEvent {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(source, "source");
    }
}
