package outbox.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import outbox.spi.MetricsExporter;

import java.util.List;
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
public final class MicrometerMetricsExporter implements MetricsExporter, AutoCloseable {

  private final MeterRegistry registry;
  private final Counter hotEnqueued;
  private final Counter hotDropped;
  private final Counter coldEnqueued;
  private final Counter dispatchSuccess;
  private final Counter dispatchFailure;
  private final Counter dispatchDead;
  private final Gauge hotDepthGauge;
  private final Gauge coldDepthGauge;
  private final Gauge lagGauge;

  private final AtomicInteger hotDepth = new AtomicInteger();
  private final AtomicInteger coldDepth = new AtomicInteger();
  private final AtomicLong oldestLagMs = new AtomicLong();
  private volatile boolean closed;

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
    if (namePrefix.endsWith(".")) {
      throw new IllegalArgumentException("namePrefix must not end with '.'");
    }

    this.registry = registry;
    this.hotEnqueued = Counter.builder(namePrefix + ".enqueue.hot")
        .description("Events enqueued via hot path")
        .register(registry);
    this.hotDropped = Counter.builder(namePrefix + ".enqueue.hot.dropped")
        .description("Events dropped (hot queue full)")
        .register(registry);
    this.coldEnqueued = Counter.builder(namePrefix + ".enqueue.cold")
        .description("Events enqueued via cold (poller) path")
        .register(registry);
    this.dispatchSuccess = Counter.builder(namePrefix + ".dispatch.success")
        .description("Events dispatched successfully")
        .register(registry);
    this.dispatchFailure = Counter.builder(namePrefix + ".dispatch.failure")
        .description("Events failed (will retry)")
        .register(registry);
    this.dispatchDead = Counter.builder(namePrefix + ".dispatch.dead")
        .description("Events moved to DEAD")
        .register(registry);

    this.hotDepthGauge = Gauge.builder(namePrefix + ".queue.hot.depth", hotDepth, AtomicInteger::get)
        .register(registry);
    this.coldDepthGauge = Gauge.builder(namePrefix + ".queue.cold.depth", coldDepth, AtomicInteger::get)
        .register(registry);
    this.lagGauge = Gauge.builder(namePrefix + ".lag.oldest.ms", oldestLagMs, AtomicLong::get)
        .register(registry);
  }

  @Override
  public void incrementHotEnqueued() {
    if (closed) return;
    hotEnqueued.increment();
  }

  @Override
  public void incrementHotDropped() {
    if (closed) return;
    hotDropped.increment();
  }

  @Override
  public void incrementColdEnqueued() {
    if (closed) return;
    coldEnqueued.increment();
  }

  @Override
  public void incrementDispatchSuccess() {
    if (closed) return;
    dispatchSuccess.increment();
  }

  @Override
  public void incrementDispatchFailure() {
    if (closed) return;
    dispatchFailure.increment();
  }

  @Override
  public void incrementDispatchDead() {
    if (closed) return;
    dispatchDead.increment();
  }

  @Override
  public void recordQueueDepths(int hotDepth, int coldDepth) {
    if (closed) return;
    this.hotDepth.set(hotDepth);
    this.coldDepth.set(coldDepth);
  }

  @Override
  public void recordOldestLagMs(long lagMs) {
    if (closed) return;
    this.oldestLagMs.set(lagMs);
  }

  /**
   * Removes all meters registered by this exporter from the registry.
   *
   * <p>Call this when the exporter is no longer needed (e.g. when the
   * {@link outbox.Outbox} is closed) to prevent stale gauges.
   */
  @Override
  public void close() {
    closed = true;
    RuntimeException first = null;
    for (Meter meter : List.of(hotEnqueued, hotDropped, coldEnqueued,
        dispatchSuccess, dispatchFailure, dispatchDead,
        hotDepthGauge, coldDepthGauge, lagGauge)) {
      try {
        registry.remove(meter);
      } catch (RuntimeException e) {
        if (first == null) first = e; else first.addSuppressed(e);
      }
    }
    if (first != null) throw first;
  }
}
