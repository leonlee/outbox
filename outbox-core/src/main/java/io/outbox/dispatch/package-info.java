/**
 * Hot-path event dispatcher with dual-queue processing.
 *
 * <p>{@link io.outbox.dispatch.OutboxDispatcher} drains a hot queue (after-commit callbacks) and a
 * cold queue (poller fallback) using fair 2:1 weighted round-robin. Supports retry with
 * exponential backoff, in-flight deduplication, event interception, and graceful shutdown.
 *
 * @see io.outbox.dispatch.OutboxDispatcher
 * @see io.outbox.dispatch.RetryPolicy
 * @see io.outbox.dispatch.EventInterceptor
 * @see io.outbox.dispatch.QueuedEvent
 */
package io.outbox.dispatch;
