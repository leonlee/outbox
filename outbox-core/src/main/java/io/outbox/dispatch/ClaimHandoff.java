package io.outbox.dispatch;

import io.outbox.dispatch.QueuedEvent.ClaimLease;

import java.util.function.ToIntFunction;

/**
 * The write authority of one reserved copy of an event: which lease its status write is fenced on.
 *
 * <p>A copy starts with its own lease (none for a hot copy). The poller can claim the same row
 * again while that copy is queued or running here — its own writer stamp, or a lease that expired
 * under a slow listener — and the newer copy is then turned away. Its lease is what the row now
 * carries, so it is handed to the copy that will write, or the write would miss and leave the row
 * claimed until the lock timeout.
 *
 * <p>Every transition holds this object's monitor, which serialises three things that would
 * otherwise race: a hand-over ({@link #adopt}), the status write that consumes it
 * ({@link #write}), and a newer reservation taking over ({@link #retire}). So a hand-over lands
 * before the write and is used, or is refused and the caller releases its lease. A retired copy
 * keeps only its own lease, so a superseded attempt can never write under a newer claim.
 */
final class ClaimHandoff {
    private final ClaimLease own;
    private ClaimLease adopted; // guarded by this
    private boolean closed; // guarded by this
    private boolean retired; // guarded by this

    ClaimHandoff(ClaimLease own) {
        this.own = own;
    }

    /**
     * Takes over a newer claim of the row.
     *
     * @return {@code true} if this copy now holds {@code lease} — taken over, or already held (a
     *         duplicate of the claim it writes under); {@code false} if it has written, been
     *         abandoned or been superseded, or {@code lease} is older than the one it holds
     */
    synchronized boolean adopt(ClaimLease lease) {
        if (closed || retired) {
            return false;
        }
        ClaimLease current = adopted != null ? adopted : own;
        if (lease.equals(current)) {
            return true;
        }
        if (current != null && !lease.claimedAt().isAfter(current.claimedAt())) {
            return false;
        }
        adopted = lease;
        return true;
    }

    /**
     * Supersedes this copy for a newer reservation of the same event, before that one is published.
     *
     * @return the adopted lease, which now belongs to the newer reservation, or {@code null}
     */
    synchronized ClaimLease retire() {
        retired = true;
        ClaimLease carried = closed ? null : adopted;
        adopted = null;
        return carried;
    }

    /**
     * Runs the status write under the lease this copy holds now ({@code null} for an unclaimed hot
     * copy), and ends its authority.
     */
    synchronized int write(ToIntFunction<ClaimLease> op) {
        try {
            return op.applyAsInt(adopted != null ? adopted : own);
        } finally {
            closed = true;
        }
    }

    /**
     * Ends the authority of a copy that will not write.
     *
     * @return a lease handed to it that nobody will now write under — the caller releases it — or
     *         {@code null}
     */
    synchronized ClaimLease abandon() {
        if (closed) {
            return null;
        }
        closed = true;
        ClaimLease orphan = adopted;
        adopted = null;
        return orphan;
    }
}
