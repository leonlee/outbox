package outbox.core.api;

public interface OutboxMetrics {
  OutboxMetrics NOOP = new NoopOutboxMetrics();

  void incrementHotEnqueued();

  void incrementHotDropped();

  void incrementColdEnqueued();

  void incrementDispatchSuccess();

  void incrementDispatchFailure();

  void incrementDispatchDead();

  void recordQueueDepths(int hotDepth, int coldDepth);

  void recordOldestLagMs(long lagMs);
}
