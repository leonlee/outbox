/**
 * Micrometer bridge for exporting outbox metrics to Prometheus, Grafana, and other backends.
 *
 * <p>{@link io.outbox.micrometer.MicrometerMetricsExporter} implements the
 * {@link io.outbox.spi.MetricsExporter} SPI using Micrometer counters and gauges.
 *
 * @see io.outbox.micrometer.MicrometerMetricsExporter
 */
package io.outbox.micrometer;
