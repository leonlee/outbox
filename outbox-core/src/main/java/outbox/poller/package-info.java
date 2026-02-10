/**
 * Scheduled database poller as a fallback delivery mechanism.
 *
 * <p>{@link outbox.poller.OutboxPoller} periodically scans for pending events that were not
 * delivered via the hot path, using claim-based locking for safe multi-instance deployments.
 *
 * @see outbox.poller.OutboxPoller
 * @see outbox.poller.OutboxPollerHandler
 */
package outbox.poller;
