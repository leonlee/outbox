/**
 * Dead event tooling for querying, counting, and replaying events stuck in DEAD status.
 *
 * <p>{@link outbox.dead.DeadEventManager} provides a connection-managed facade over
 * the {@link outbox.spi.OutboxStore} dead event methods.
 *
 * @see outbox.dead.DeadEventManager
 * @see outbox.spi.OutboxStore#queryDead
 */
package outbox.dead;
