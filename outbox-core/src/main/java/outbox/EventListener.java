package outbox;

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
 * {@link outbox.dispatch.EventInterceptor} instead of a listener.
 *
 * <h2>Idempotency</h2>
 * <p>Listeners may be invoked multiple times for the same event (at-least-once
 * delivery). Use {@link EventEnvelope#eventId()} for deduplication.
 *
 * <h2>Example Implementations</h2>
 * <pre>{@code
 * // Publish to Kafka
 * registry.register("OrderCreated", event -> {
 *   kafkaTemplate.send("orders", event.eventId(), event.payloadJson());
 * });
 *
 * // Update read model with aggregate-scoped registration
 * registry.register("User", "UserUpdated", event -> {
 *   userCache.invalidate(event.aggregateId());
 * });
 * }</pre>
 *
 * @see outbox.registry.ListenerRegistry
 * @see outbox.registry.DefaultListenerRegistry
 */
public interface EventListener {

  /**
   * Processes an outbox event.
   *
   * @param event the event envelope containing type, payload, and metadata
   * @throws Exception if processing fails; triggers retry or dead-letter handling
   */
  void onEvent(EventEnvelope event) throws Exception;
}
