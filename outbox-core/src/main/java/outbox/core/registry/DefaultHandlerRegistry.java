package outbox.core.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DefaultHandlerRegistry implements HandlerRegistry {
  public static final String ALL_EVENTS = "*";

  private final Map<String, CopyOnWriteArrayList<Handler>> handlers = new ConcurrentHashMap<>();

  public DefaultHandlerRegistry register(String eventType, Handler handler) {
    handlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    return this;
  }

  public DefaultHandlerRegistry registerAll(Handler handler) {
    return register(ALL_EVENTS, handler);
  }

  @Override
  public List<Handler> handlersFor(String eventType) {
    List<Handler> result = new ArrayList<>();
    CopyOnWriteArrayList<Handler> specific = handlers.get(eventType);
    if (specific != null) {
      result.addAll(specific);
    }
    CopyOnWriteArrayList<Handler> all = handlers.get(ALL_EVENTS);
    if (all != null) {
      result.addAll(all);
    }
    return result;
  }
}
