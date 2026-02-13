package outbox.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MicrometerMetricsExporterTest {

  private SimpleMeterRegistry registry;
  private MicrometerMetricsExporter exporter;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    exporter = new MicrometerMetricsExporter(registry);
  }

  @Test
  void incrementHotEnqueued() {
    exporter.incrementHotEnqueued();
    exporter.incrementHotEnqueued();
    assertEquals(2.0, counter("outbox.enqueue.hot").count());
  }

  @Test
  void incrementHotDropped() {
    exporter.incrementHotDropped();
    assertEquals(1.0, counter("outbox.enqueue.hot.dropped").count());
  }

  @Test
  void incrementColdEnqueued() {
    exporter.incrementColdEnqueued();
    exporter.incrementColdEnqueued();
    exporter.incrementColdEnqueued();
    assertEquals(3.0, counter("outbox.enqueue.cold").count());
  }

  @Test
  void incrementDispatchSuccess() {
    exporter.incrementDispatchSuccess();
    assertEquals(1.0, counter("outbox.dispatch.success").count());
  }

  @Test
  void incrementDispatchFailure() {
    exporter.incrementDispatchFailure();
    assertEquals(1.0, counter("outbox.dispatch.failure").count());
  }

  @Test
  void incrementDispatchDead() {
    exporter.incrementDispatchDead();
    assertEquals(1.0, counter("outbox.dispatch.dead").count());
  }

  @Test
  void recordQueueDepths() {
    exporter.recordQueueDepths(42, 7);
    assertEquals(42.0, gauge("outbox.queue.hot.depth").value());
    assertEquals(7.0, gauge("outbox.queue.cold.depth").value());

    exporter.recordQueueDepths(0, 0);
    assertEquals(0.0, gauge("outbox.queue.hot.depth").value());
    assertEquals(0.0, gauge("outbox.queue.cold.depth").value());
  }

  @Test
  void recordOldestLagMs() {
    exporter.recordOldestLagMs(12345L);
    assertEquals(12345.0, gauge("outbox.lag.oldest.ms").value());

    exporter.recordOldestLagMs(0L);
    assertEquals(0.0, gauge("outbox.lag.oldest.ms").value());
  }

  @Test
  void customNamePrefix() {
    var custom = new MicrometerMetricsExporter(registry, "orders.outbox");
    custom.incrementHotEnqueued();
    custom.recordQueueDepths(10, 5);
    custom.recordOldestLagMs(500L);

    assertEquals(1.0, counter("orders.outbox.enqueue.hot").count());
    assertEquals(10.0, gauge("orders.outbox.queue.hot.depth").value());
    assertEquals(5.0, gauge("orders.outbox.queue.cold.depth").value());
    assertEquals(500.0, gauge("orders.outbox.lag.oldest.ms").value());
  }

  @Test
  void nullRegistryThrows() {
    assertThrows(NullPointerException.class, () -> new MicrometerMetricsExporter(null));
  }

  @Test
  void nullPrefixThrows() {
    assertThrows(NullPointerException.class, () -> new MicrometerMetricsExporter(registry, null));
  }

  private Counter counter(String name) {
    Counter c = registry.find(name).counter();
    assertNotNull(c, "Counter not found: " + name);
    return c;
  }

  private Gauge gauge(String name) {
    Gauge g = registry.find(name).gauge();
    assertNotNull(g, "Gauge not found: " + name);
    return g;
  }
}
