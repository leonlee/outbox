package io.outbox.dispatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ConcurrentHashMap}-based in-flight tracker with optional time-based expiry.
 *
 * <p>When {@code ttlMs} is zero (the default), an event remains tracked until explicitly
 * released. When positive, stale entries are automatically reclaimed after the TTL elapses,
 * allowing re-processing of events stuck in a failed worker.
 *
 * <p>Entries carry two states: <em>in flight</em> (a worker holds the event now) and
 * <em>settled</em> ({@link #markSettled} — the event completed and the entry lingers until the
 * TTL expires it, so a late second copy is rejected). {@link #isSettled} tells them apart; a
 * settled marker's TTL restarts at settlement, because the window that matters is "recently
 * finished", not "long ago acquired".
 *
 * <p>This class is thread-safe.
 */
public final class DefaultInFlightTracker implements InFlightTracker {
    /**
     * @param at      when this entry was stamped — for {@code tryAcquire} that is acquisition time,
     *                for a settled marker it restarts at settlement, since the window we care about
     *                is "recently finished", not "long ago acquired"
     * @param settled {@code false} while a worker holds it, {@code true} once it has completed
     */
    private record Entry(long at, boolean settled, long token) {
    }

    /**
     * Bounds CAS retries in {@link #acquire} under pathological same-event contention.
     *
     * <p>Not a correctness knob — bailing out returns {@link #NOT_ACQUIRED}, which is the
     * conservative answer (the copy is dropped and the next poll retries). Normal acquisition
     * completes on the first attempt; a thread that loses the expired-entry reclaim CAS almost
     * always finds the winner's fresh entry on its second look and returns {@code NOT_ACQUIRED}
     * there. Three = that pair plus one spare; the bound only exists so a thread that keeps losing
     * cannot spin on the CPU indefinitely.
     */
    private static final int MAX_ACQUIRE_ATTEMPTS = 3;

    private final Map<String, Entry> inflight = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final AtomicInteger evictCounter = new AtomicInteger();
    /** Distinguishes one acquisition of an id from the next, so a late completion cannot end it. */
    private final java.util.concurrent.atomic.AtomicLong tokens = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Creates a tracker with no TTL (entries persist until released).
     */
    public DefaultInFlightTracker() {
        this.ttlMs = 0L;
    }

    /**
     * Creates a tracker with a time-to-live for stale entries.
     *
     * @param ttlMs time-to-live in milliseconds; entries older than this are reclaimable. Zero or
     *              a negative value disables expiry, exactly like {@link #DefaultInFlightTracker()}
     */
    public DefaultInFlightTracker(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public boolean tryAcquire(String eventId) {
        return acquire(eventId) != NOT_ACQUIRED;
    }

    @Override
    public long acquire(String eventId) {
        long now = System.currentTimeMillis();
        maybeEvictExpired(now);
        for (int attempt = 0; attempt < MAX_ACQUIRE_ATTEMPTS; attempt++) {
            long token = nextToken();
            Entry existing = inflight.putIfAbsent(eventId, new Entry(now, false, token));
            if (existing == null) {
                return token;
            }
            if (!isExpired(existing, now)) {
                return NOT_ACQUIRED;
            }
            now = System.currentTimeMillis();
            if (inflight.replace(eventId, existing, new Entry(now, false, token))) {
                return token;
            }
            // CAS lost — another thread reclaimed the expired entry first; loop for a fresh look
        }
        return NOT_ACQUIRED;
    }

    private void maybeEvictExpired(long now) {
        if (ttlMs <= 0) {
            return;
        }
        // Evict periodically: every ~1000 acquires (lightweight sampling)
        if ((evictCounter.incrementAndGet() & 0x3FF) != 0) {
            return;
        }
        inflight.entrySet().removeIf(e -> now - e.getValue().at() > ttlMs * 2);
    }

    @Override
    public void release(String eventId) {
        inflight.remove(eventId);
    }

    @Override
    public void release(String eventId, long token) {
        // Only if this is still the same acquisition. After a TTL reclamation the entry belongs to
        // a second worker, and removing it would let a third copy in while that one still runs.
        inflight.computeIfPresent(eventId, (id, entry) -> entry.token() == token ? null : entry);
    }

    @Override
    public long settledToken(String eventId) {
        Entry entry = inflight.get(eventId);
        return entry != null && entry.settled() ? entry.token() : NOT_ACQUIRED;
    }

    @Override
    public void releaseSettled(String eventId, long token) {
        inflight.computeIfPresent(eventId,
                (id, entry) -> entry.settled() && entry.token() == token ? null : entry);
    }

    @Override
    public void markSettled(String eventId, long token) {
        inflight.computeIfPresent(eventId, (id, entry) -> entry.token() == token
                ? new Entry(System.currentTimeMillis(), true, entry.token())
                : entry);
    }

    @Override
    public void markSettled(String eventId) {
        inflight.computeIfPresent(eventId,
                (id, entry) -> new Entry(System.currentTimeMillis(), true, entry.token()));
    }

    @Override
    public boolean isRunning(String eventId) {
        Entry entry = inflight.get(eventId);
        if (entry == null || entry.settled()) {
            return false;
        }
        // Expire on exactly the rule tryAcquire reclaims on. An entry older than the TTL is one
        // whose worker never came back, and tryAcquire deliberately hands such an event out again;
        // reporting it as still running would let a caller drop the redelivery before it ever got
        // that far, and the event would never move again.
        return !isExpired(entry, System.currentTimeMillis());
    }

    /**
     * The next acquisition token — always strictly positive.
     *
     * <p>The counter wraps at {@code Long.MAX_VALUE} by two's complement, which the {@code ==}
     * generation check would not mind, but the negative range contains the
     * {@link #NOT_ACQUIRED} sentinel: an acquisition minted {@code -1} would read as a refusal to
     * every caller. Rather than special-case one value, tokens are kept positive outright — on a
     * wrap the counter is CAS-reset to zero (one thread wins, the rest re-increment), so the guard
     * never spins through the negative range. Unreachable in practice (2^63 increments), cheap
     * anyway.
     */
    private long nextToken() {
        while (true) {
            long token = tokens.incrementAndGet();
            if (token > 0) {
                return token;
            }
            tokens.compareAndSet(token, 0);
        }
    }

    private boolean isExpired(Entry entry, long now) {
        return ttlMs > 0 && now - entry.at() > ttlMs;
    }

    @Override
    public boolean isSettled(String eventId) {
        Entry entry = inflight.get(eventId);
        return entry != null && entry.settled();
    }

    @Override
    public boolean hasTtl() {
        return ttlMs > 0;
    }
}
