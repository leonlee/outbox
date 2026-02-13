/**
 * Micrometer bridge for exporting outbox metrics to Prometheus, Grafana, and other backends.
 *
 * <p>{@link outbox.micrometer.MicrometerMetricsExporter} implements the
 * {@link outbox.spi.MetricsExporter} SPI using Micrometer counters and gauges.
 *
 * @see outbox.micrometer.MicrometerMetricsExporter
 */
package outbox.micrometer;
