package outbox.registry;

import outbox.AggregateType;
import outbox.EventListener;
import outbox.EventType;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping (aggregateType, eventType) pairs to listeners.
 *
 * <p>Each (aggregateType, eventType) pair maps to exactly one listener.
 * Duplicate registration throws {@link IllegalStateException}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ListenerRegistry registry = new DefaultListenerRegistry()
 *     .register("UserCreated", event -> kafka.send(event))
 *     .register(Aggregates.ORDER, "OrderPlaced", event -> process(event));
 * }</pre>
 *
 * <p>The convenience {@code register(eventType, listener)} overloads use
 * {@link AggregateType#GLOBAL} as the aggregate type.
 *
 * @see EventListener
 * @see ListenerRegistry
 */
public final class DefaultListenerRegistry implements ListenerRegistry {

  private final ConcurrentHashMap<String, EventListener> listeners = new ConcurrentHashMap<>();

  /**
   * Registers a listener for a specific (aggregateType, eventType) pair.
   *
   * @param aggregateType the aggregate type name
   * @param eventType the event type name
   * @param listener the listener
   * @return this registry for chaining
   * @throws IllegalStateException if a listener is already registered for this pair
   */
  public DefaultListenerRegistry register(String aggregateType, String eventType, EventListener listener) {
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(listener, "listener");
    String key = aggregateType + ":" + eventType;
    EventListener prev = listeners.putIfAbsent(key, listener);
    if (prev != null) {
      throw new IllegalStateException("Duplicate: " + key);
    }
    return this;
  }

  /**
   * Registers a listener for a type-safe (aggregateType, eventType) pair.
   */
  public DefaultListenerRegistry register(AggregateType aggregateType, EventType eventType, EventListener listener) {
    return register(aggregateType.name(), eventType.name(), listener);
  }

  /**
   * Registers a listener for a type-safe aggregateType and string eventType.
   */
  public DefaultListenerRegistry register(AggregateType aggregateType, String eventType, EventListener listener) {
    return register(aggregateType.name(), eventType, listener);
  }

  /**
   * Registers a listener using {@link AggregateType#GLOBAL} as the aggregate type.
   *
   * @param eventType the event type name
   * @param listener the listener
   * @return this registry for chaining
   */
  public DefaultListenerRegistry register(String eventType, EventListener listener) {
    return register(AggregateType.GLOBAL.name(), eventType, listener);
  }

  /**
   * Registers a listener using {@link AggregateType#GLOBAL} as the aggregate type.
   *
   * @param eventType the event type
   * @param listener the listener
   * @return this registry for chaining
   */
  public DefaultListenerRegistry register(EventType eventType, EventListener listener) {
    return register(AggregateType.GLOBAL.name(), eventType.name(), listener);
  }

  @Override
  public EventListener listenerFor(String aggregateType, String eventType) {
    return listeners.get(aggregateType + ":" + eventType);
  }
}
