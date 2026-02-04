package outbox.core.registry;

import java.util.List;

public interface PublisherRegistry {
  List<Publisher> publishersFor(String eventType);
}
