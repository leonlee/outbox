/**
 * Service Provider Interfaces (SPI) for extending the outbox framework.
 *
 * <p>These interfaces define the extension points that integrators implement
 * to plug in transaction management, connection provisioning, persistence, and metrics.
 *
 * @see outbox.spi.TxContext
 * @see outbox.spi.ConnectionProvider
 * @see outbox.spi.EventStore
 * @see outbox.spi.MetricsExporter
 */
package outbox.spi;
