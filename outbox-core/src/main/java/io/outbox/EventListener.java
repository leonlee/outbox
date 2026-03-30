package io.outbox;

/**
 * Listener that reacts to outbox events.
 *
 * <p>Implementations can perform any action: publish to message brokers,
 * update caches, call external services, or process locally. Each
 * (aggregateType, eventType) pair maps to exactly one listener.
 *
 * <h2>Execution Model</h2>
 * <p>Listeners are executed <b>synchronously</b> on dispatcher worker threads.
 * This provides natural backpressure: slow listeners cause queues to fill,
 * which gracefully degrades to poller-based recovery.
 *
 * <h2>Error Handling</h2>
 * <p>If a listener throws an exception:
 * <ul>
 *   <li>The event is marked for RETRY with exponential backoff</li>
 *   <li>After max attempts, the event is marked DEAD</li>
 * </ul>
 *
 * <p>If no listener is registered for an event's (aggregateType, eventType),
 * the event is immediately marked DEAD (no retry).
 *
 * <h2>Cross-Cutting Concerns</h2>
 * <p>For audit logging, metrics, and other cross-cutting behavior, use
 * {@link io.outbox.dispatch.EventInterceptor} instead of a listener.
 *
 * <h2>Idempotency</h2>
 * <p>Listeners may be invoked multiple times for the same event (at-least-once
 * delivery). Use {@link EventEnvelope#eventId()} for deduplication.
 *
 * <h2>Example Implementations</h2>
 * <pre>{@code
 * // Publish to Kafka — returns DONE on success
 * registry.register("OrderCreated", event -> {
 *   kafkaTemplate.send("orders", event.eventId(), event.payloadJson());
 *   return DispatchResult.done();
 * });
 *
 * // Deferred retry when rate-limited
 * registry.register("Webhook", event -> {
 *   if (rateLimiter.isLimited()) {
 *     return DispatchResult.retryAfter(Duration.ofMinutes(1));
 *   }
 *   webhookClient.send(event.payloadJson());
 *   return DispatchResult.done();
 * });
 * }</pre>
 *
 * @see DispatchResult
 * @see io.outbox.registry.ListenerRegistry
 * @see io.outbox.registry.DefaultListenerRegistry
 */
@FunctionalInterface
public interface EventListener {

    /**
     * Processes an outbox envelope and returns a {@link DispatchResult} to control
     * post-dispatch behavior.
     *
     * <p>Return {@link DispatchResult#done()} on success,
     * {@link DispatchResult#retryAfter(java.time.Duration)} for deferred re-delivery
     * without counting against {@code maxAttempts}, or {@link DispatchResult#dead(String)}
     * to immediately mark the event as DEAD without retry.
     *
     * @param envelope the event envelope containing type, payload, and metadata
     * @return the dispatch result indicating completion, deferred retry, or immediate dead-letter;
     * must not be {@code null} (a null return is treated as a programming error and triggers retry)
     * @throws Exception if processing fails; triggers retry or dead-letter handling
     * @see DispatchResult
     */
    DispatchResult onEvent(EventEnvelope envelope) throws Exception;
}
