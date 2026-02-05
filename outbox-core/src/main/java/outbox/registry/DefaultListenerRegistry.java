package outbox.registry;

import outbox.EventListener;
import outbox.EventType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry for event listeners.
 *
 * <p>Supports registration by specific event type or wildcard ("*") for all events.
 * Listeners are invoked in registration order.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ListenerRegistry registry = new DefaultListenerRegistry()
 *     // Type-specific listeners
 *     .register("OrderCreated", event -> kafka.send(event))
 *     .register("OrderCreated", event -> cache.invalidate(event))
 *     .register(UserEvents.USER_DELETED, event -> cleanup(event))
 *
 *     // Wildcard listener for audit/logging
 *     .registerAll(event -> audit.log(event));
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This implementation is thread-safe. Registrations can be made concurrently,
 * and lookups return consistent snapshots.
 *
 * @see EventListener
 * @see ListenerRegistry
 */
public final class DefaultListenerRegistry implements ListenerRegistry {
  public static final String ALL_EVENTS = "*";

  private final Map<String, CopyOnWriteArrayList<EventListener>> listeners = new ConcurrentHashMap<>();

  /**
   * Registers a listener for a type-safe event type.
   *
   * @param eventType the event type (enum or other EventType implementation)
   * @param listener the listener
   * @return this registry for chaining
   */
  public DefaultListenerRegistry register(EventType eventType, EventListener listener) {
    return register(eventType.name(), listener);
  }

  /**
   * Registers a listener for a string event type.
   *
   * @param eventType the event type name
   * @param listener the listener
   * @return this registry for chaining
   */
  public DefaultListenerRegistry register(String eventType, EventListener listener) {
    listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(listener);
    return this;
  }

  /**
   * Registers a listener for all event types (wildcard).
   *
   * @param listener the listener
   * @return this registry for chaining
   */
  public DefaultListenerRegistry registerAll(EventListener listener) {
    return register(ALL_EVENTS, listener);
  }

  @Override
  public List<EventListener> listenersFor(String eventType) {
    List<EventListener> result = new ArrayList<>();
    CopyOnWriteArrayList<EventListener> specific = listeners.get(eventType);
    if (specific != null) {
      result.addAll(specific);
    }
    CopyOnWriteArrayList<EventListener> all = listeners.get(ALL_EVENTS);
    if (all != null) {
      result.addAll(all);
    }
    return Collections.unmodifiableList(result);
  }
}
