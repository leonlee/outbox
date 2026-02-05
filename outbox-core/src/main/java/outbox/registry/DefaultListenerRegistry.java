package outbox.registry;

import outbox.EventListener;
import outbox.EventType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;

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

  private final CopyOnWriteArrayList<Registration> registrations = new CopyOnWriteArrayList<>();

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
    registrations.add(new Registration(eventType, listener));
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
    for (Registration registration : registrations) {
      if (ALL_EVENTS.equals(registration.eventType)
          || Objects.equals(registration.eventType, eventType)) {
        result.add(registration.listener);
      }
    }
    return Collections.unmodifiableList(result);
  }

  private static final class Registration {
    private final String eventType;
    private final EventListener listener;

    private Registration(String eventType, EventListener listener) {
      this.eventType = eventType;
      this.listener = listener;
    }
  }
}
