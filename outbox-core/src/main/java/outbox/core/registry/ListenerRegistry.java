package outbox.core.registry;

import java.util.List;

/**
 * Registry for looking up event listeners by event type.
 */
public interface ListenerRegistry {
  List<EventListener> listenersFor(String eventType);
}
