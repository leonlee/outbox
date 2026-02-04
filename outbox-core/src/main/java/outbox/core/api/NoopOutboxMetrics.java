package outbox.core.api;

public final class NoopOutboxMetrics implements OutboxMetrics {
  @Override
  public void incrementHotEnqueued() {
  }

  @Override
  public void incrementHotDropped() {
  }

  @Override
  public void incrementColdEnqueued() {
  }

  @Override
  public void incrementDispatchSuccess() {
  }

  @Override
  public void incrementDispatchFailure() {
  }

  @Override
  public void incrementDispatchDead() {
  }

  @Override
  public void recordQueueDepths(int hotDepth, int coldDepth) {
  }

  @Override
  public void recordOldestLagMs(long lagMs) {
  }
}
