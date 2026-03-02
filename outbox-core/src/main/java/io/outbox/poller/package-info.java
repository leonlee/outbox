/**
 * Scheduled database poller as a fallback delivery mechanism.
 *
 * <p>{@link io.outbox.poller.OutboxPoller} periodically scans for pending events that were not
 * delivered via the hot path, using claim-based locking for safe multi-instance deployments.
 *
 * @see io.outbox.poller.OutboxPoller
 * @see io.outbox.poller.OutboxPollerHandler
 */
package io.outbox.poller;
