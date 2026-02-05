package outbox.spi;

public interface MetricsExporter {
  MetricsExporter NOOP = new Noop();

  void incrementHotEnqueued();

  void incrementHotDropped();

  void incrementColdEnqueued();

  void incrementDispatchSuccess();

  void incrementDispatchFailure();

  void incrementDispatchDead();

  void recordQueueDepths(int hotDepth, int coldDepth);

  void recordOldestLagMs(long lagMs);

  final class Noop implements MetricsExporter {
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
}
