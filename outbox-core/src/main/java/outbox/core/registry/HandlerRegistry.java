package outbox.core.registry;

import java.util.List;

public interface HandlerRegistry {
  List<Handler> handlersFor(String eventType);
}
