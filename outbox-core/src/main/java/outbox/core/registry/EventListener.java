package outbox.core.registry;

import outbox.core.api.EventEnvelope;

/**
 * Listener that reacts to outbox events.
 *
 * <p>Implementations can perform any action: publish to message brokers,
 * update caches, call external services, or process locally. The framework
 * makes no distinction between "publishers" and "handlers" - all listeners
 * are treated equally.
 */
public interface EventListener {
  void onEvent(EventEnvelope event) throws Exception;
}
