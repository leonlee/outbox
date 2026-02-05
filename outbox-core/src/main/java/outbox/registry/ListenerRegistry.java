package outbox.registry;

import outbox.EventListener;

import java.util.List;

/**
 * Registry for looking up event listeners by event type.
 *
 * <p>The dispatcher uses this registry to find all listeners that should
 * process a given event. Listeners are returned in a deterministic order
 * and executed sequentially.
 *
 * @see EventListener
 * @see DefaultListenerRegistry
 */
public interface ListenerRegistry {

  /**
   * Returns all listeners registered for the given event type.
   *
   * <p>The returned list includes:
   * <ol>
   *   <li>Listeners registered for the exact event type</li>
   *   <li>Listeners registered for all events (wildcard "*")</li>
   * </ol>
   *
   * @param eventType the event type to look up
   * @return immutable list of matching listeners, may be empty
   */
  List<EventListener> listenersFor(String eventType);
}
