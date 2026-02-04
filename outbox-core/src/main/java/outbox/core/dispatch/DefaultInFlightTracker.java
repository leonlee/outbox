package outbox.core.dispatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultInFlightTracker implements InFlightTracker {
  private final Map<String, Long> inflight = new ConcurrentHashMap<>();
  private final long ttlMs;

  public DefaultInFlightTracker() {
    this.ttlMs = 0L;
  }

  public DefaultInFlightTracker(long ttlMs) {
    this.ttlMs = ttlMs;
  }

  @Override
  public boolean tryAcquire(String eventId) {
    long now = System.currentTimeMillis();
   for (int attempt = 0; attempt < 10; attempt++) {
      Long existing = inflight.putIfAbsent(eventId, now);
      if (existing == null) {
        return true;
      }
      if (ttlMs > 0 && now - existing > ttlMs) {
        if (inflight.replace(eventId, existing, now)) {
          return true;
        }
        Thread.yield();
        continue;
      }
      return false;
    }
    return false;
  }

  @Override
  public void release(String eventId) {
    inflight.remove(eventId);
  }
}
