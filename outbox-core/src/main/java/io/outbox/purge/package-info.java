/**
 * Scheduled purge of terminal outbox events to prevent table bloat.
 *
 * <p>{@link io.outbox.purge.OutboxPurgeScheduler} periodically deletes DONE and DEAD events
 * older than a configurable retention period, using batch deletes to limit lock duration.
 *
 * @see io.outbox.purge.OutboxPurgeScheduler
 * @see io.outbox.spi.EventPurger
 */
package io.outbox.purge;
