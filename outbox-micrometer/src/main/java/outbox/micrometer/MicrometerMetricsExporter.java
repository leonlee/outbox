package outbox.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import outbox.spi.MetricsExporter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-based implementation of {@link MetricsExporter}.
 *
 * <p>Registers counters and gauges with a {@link MeterRegistry} for export to
 * Prometheus, Grafana, Datadog, and other monitoring backends.
 *
 * <h3>Counters</h3>
 * <ul>
 *   <li>{@code outbox.enqueue.hot} — events enqueued via hot path</li>
 *   <li>{@code outbox.enqueue.hot.dropped} — events dropped (hot queue full)</li>
 *   <li>{@code outbox.enqueue.cold} — events enqueued via cold (poller) path</li>
 *   <li>{@code outbox.dispatch.success} — events dispatched successfully</li>
 *   <li>{@code outbox.dispatch.failure} — events failed (will retry)</li>
 *   <li>{@code outbox.dispatch.dead} — events moved to DEAD</li>
 * </ul>
 *
 * <h3>Gauges</h3>
 * <ul>
 *   <li>{@code outbox.queue.hot.depth} — current hot queue depth</li>
 *   <li>{@code outbox.queue.cold.depth} — current cold queue depth</li>
 *   <li>{@code outbox.lag.oldest.ms} — lag of oldest pending event in milliseconds</li>
 * </ul>
 *
 * @see MetricsExporter
 */
public final class MicrometerMetricsExporter implements MetricsExporter {

  private final Counter hotEnqueued;
  private final Counter hotDropped;
  private final Counter coldEnqueued;
  private final Counter dispatchSuccess;
  private final Counter dispatchFailure;
  private final Counter dispatchDead;

  private final AtomicInteger hotDepth = new AtomicInteger();
  private final AtomicInteger coldDepth = new AtomicInteger();
  private final AtomicLong oldestLagMs = new AtomicLong();

  /**
   * Creates an exporter with the default metric name prefix {@code "outbox"}.
   *
   * @param registry the Micrometer meter registry
   */
  public MicrometerMetricsExporter(MeterRegistry registry) {
    this(registry, "outbox");
  }

  /**
   * Creates an exporter with a custom metric name prefix for multi-instance use.
   *
   * @param registry   the Micrometer meter registry
   * @param namePrefix prefix for all meter names (e.g. {@code "orders.outbox"})
   */
  public MicrometerMetricsExporter(MeterRegistry registry, String namePrefix) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(namePrefix, "namePrefix");
    if (namePrefix.isEmpty()) {
      throw new IllegalArgumentException("namePrefix must not be empty");
    }

    this.hotEnqueued = registry.counter(namePrefix + ".enqueue.hot");
    this.hotDropped = registry.counter(namePrefix + ".enqueue.hot.dropped");
    this.coldEnqueued = registry.counter(namePrefix + ".enqueue.cold");
    this.dispatchSuccess = registry.counter(namePrefix + ".dispatch.success");
    this.dispatchFailure = registry.counter(namePrefix + ".dispatch.failure");
    this.dispatchDead = registry.counter(namePrefix + ".dispatch.dead");

    registry.gauge(namePrefix + ".queue.hot.depth", hotDepth);
    registry.gauge(namePrefix + ".queue.cold.depth", coldDepth);
    registry.gauge(namePrefix + ".lag.oldest.ms", oldestLagMs);
  }

  @Override
  public void incrementHotEnqueued() {
    hotEnqueued.increment();
  }

  @Override
  public void incrementHotDropped() {
    hotDropped.increment();
  }

  @Override
  public void incrementColdEnqueued() {
    coldEnqueued.increment();
  }

  @Override
  public void incrementDispatchSuccess() {
    dispatchSuccess.increment();
  }

  @Override
  public void incrementDispatchFailure() {
    dispatchFailure.increment();
  }

  @Override
  public void incrementDispatchDead() {
    dispatchDead.increment();
  }

  @Override
  public void recordQueueDepths(int hotDepth, int coldDepth) {
    this.hotDepth.set(hotDepth);
    this.coldDepth.set(coldDepth);
  }

  @Override
  public void recordOldestLagMs(long lagMs) {
    this.oldestLagMs.set(lagMs);
  }
}
