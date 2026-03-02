/**
 * Service Provider Interfaces (SPI) for extending the outbox framework.
 *
 * <p>These interfaces define the extension points that integrators implement
 * to plug in transaction management, connection provisioning, persistence, purging, and metrics.
 *
 * @see io.outbox.spi.TxContext
 * @see io.outbox.spi.ConnectionProvider
 * @see io.outbox.spi.OutboxStore
 * @see io.outbox.spi.EventPurger
 * @see io.outbox.spi.MetricsExporter
 */
package io.outbox.spi;
