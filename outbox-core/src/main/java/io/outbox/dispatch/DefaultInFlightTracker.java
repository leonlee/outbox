package io.outbox.dispatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ConcurrentHashMap}-based in-flight tracker with optional time-based expiry.
 *
 * <p>When {@code ttlMs} is zero (the default), an event remains tracked until explicitly
 * released. When positive, stale entries are automatically reclaimed after the TTL elapses,
 * allowing re-processing of events stuck in a failed worker.
 *
 * <p>Timestamps are recorded with {@link System#nanoTime()} for monotonic expiry that is
 * immune to wall-clock adjustments (NTP, leap seconds).
 *
 * <p>This class is thread-safe.
 */
public final class DefaultInFlightTracker implements InFlightTracker {
    private final Map<String, Long> inflight = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final AtomicInteger evictCounter = new AtomicInteger();

    /**
     * Creates a tracker with no TTL (entries persist until released).
     */
    public DefaultInFlightTracker() {
        this.ttlNanos = 0L;
    }

    /**
     * Creates a tracker with a time-to-live for stale entries.
     *
     * @param ttlMs time-to-live in milliseconds; entries older than this are reclaimable.
     *              Non-positive values (including negative) disable expiry, behaving
     *              identically to zero
     */
    public DefaultInFlightTracker(long ttlMs) {
        this.ttlNanos = ttlMs > 0 ? TimeUnit.MILLISECONDS.toNanos(ttlMs) : 0L;
    }

    @Override
    public boolean tryAcquire(String eventId) {
        long now = System.nanoTime();
        maybeEvictExpired(now);
        for (int attempt = 0; attempt < 10; attempt++) {
            Long existing = inflight.putIfAbsent(eventId, now);
            if (existing == null) {
                return true;
            }
            if (ttlNanos > 0 && now - existing > ttlNanos) {
                now = System.nanoTime();
                if (inflight.replace(eventId, existing, now)) {
                    return true;
                }
                // CAS failed — another thread claimed it; retry
                continue;
            }
            return false;
        }
        return false;
    }

    private void maybeEvictExpired(long now) {
        if (ttlNanos <= 0) return;
        // Evict periodically: every ~256 acquires (lightweight sampling)
        if ((evictCounter.incrementAndGet() & 0xFF) != 0) return;
        inflight.entrySet().removeIf(e -> now - e.getValue() > ttlNanos);
    }

    @Override
    public void release(String eventId) {
        inflight.remove(eventId);
    }
}
