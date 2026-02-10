/**
 * Scheduled purge of terminal outbox events to prevent table bloat.
 *
 * <p>{@link outbox.purge.OutboxPurgeScheduler} periodically deletes DONE and DEAD events
 * older than a configurable retention period, using batch deletes to limit lock duration.
 *
 * @see outbox.purge.OutboxPurgeScheduler
 * @see outbox.spi.EventPurger
 */
package outbox.purge;
