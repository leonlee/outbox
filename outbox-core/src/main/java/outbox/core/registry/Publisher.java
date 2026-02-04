package outbox.core.registry;

import outbox.core.api.EventEnvelope;

public interface Publisher {
  void publish(EventEnvelope event) throws Exception;
}
