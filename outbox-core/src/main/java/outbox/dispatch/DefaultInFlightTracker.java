package outbox.dispatch;

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
 * <p>This class is thread-safe.
 */
public final class DefaultInFlightTracker implements InFlightTracker {
    private final Map<String, Long> inflight = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final AtomicInteger evictCounter = new AtomicInteger();

    /**
     * Creates a tracker with no TTL (entries persist until released).
     */
    public DefaultInFlightTracker() {
        this.ttlMs = 0L;
    }

    /**
     * Creates a tracker with a time-to-live for stale entries.
     *
     * @param ttlMs time-to-live in milliseconds; entries older than this are reclaimable
     */
    public DefaultInFlightTracker(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public boolean tryAcquire(String eventId) {
        long now = System.currentTimeMillis();
        maybeEvictExpired(now);
        for (int attempt = 0; attempt < 10; attempt++) {
            Long existing = inflight.putIfAbsent(eventId, now);
            if (existing == null) {
                return true;
            }
            if (ttlMs > 0 && now - existing > ttlMs) {
                now = System.currentTimeMillis();
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
        if (ttlMs <= 0) return;
        // Evict periodically: every ~1000 acquires (lightweight sampling)
        if ((evictCounter.incrementAndGet() & 0x3FF) != 0) return;
        inflight.entrySet().removeIf(e -> now - e.getValue() > ttlMs * 2);
    }

    @Override
    public void release(String eventId) {
        inflight.remove(eventId);
    }
}
