/**
 * Hot-path event dispatcher with dual-queue processing.
 *
 * <p>{@link outbox.dispatch.OutboxDispatcher} drains a hot queue (after-commit callbacks) and a
 * cold queue (poller fallback) using fair 2:1 weighted round-robin. Supports retry with
 * exponential backoff, in-flight deduplication, event interception, and graceful shutdown.
 *
 * @see outbox.dispatch.OutboxDispatcher
 * @see outbox.dispatch.RetryPolicy
 * @see outbox.dispatch.EventInterceptor
 * @see outbox.dispatch.QueuedEvent
 */
package outbox.dispatch;
