package outbox.core.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DefaultPublisherRegistry implements PublisherRegistry {
  public static final String ALL_EVENTS = "*";

  private final Map<String, CopyOnWriteArrayList<Publisher>> publishers = new ConcurrentHashMap<>();

  public DefaultPublisherRegistry register(String eventType, Publisher publisher) {
    publishers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(publisher);
    return this;
  }

  public DefaultPublisherRegistry registerAll(Publisher publisher) {
    return register(ALL_EVENTS, publisher);
  }

  @Override
  public List<Publisher> publishersFor(String eventType) {
    List<Publisher> result = new ArrayList<>();
    CopyOnWriteArrayList<Publisher> specific = publishers.get(eventType);
    if (specific != null) {
      result.addAll(specific);
    }
    CopyOnWriteArrayList<Publisher> all = publishers.get(ALL_EVENTS);
    if (all != null) {
      result.addAll(all);
    }
    return result;
  }
}
