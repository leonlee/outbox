package outbox.core.dispatch;

public interface InFlightTracker {
  boolean tryAcquire(String eventId);

  void release(String eventId);
}
