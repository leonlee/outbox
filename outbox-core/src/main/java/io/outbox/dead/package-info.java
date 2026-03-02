/**
 * Dead event tooling for querying, counting, and replaying events stuck in DEAD status.
 *
 * <p>{@link io.outbox.dead.DeadEventManager} provides a connection-managed facade over
 * the {@link io.outbox.spi.OutboxStore} dead event methods.
 *
 * @see io.outbox.dead.DeadEventManager
 * @see io.outbox.spi.OutboxStore#queryDead
 */
package io.outbox.dead;
