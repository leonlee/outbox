package outbox.core.registry;

import outbox.core.api.EventEnvelope;

public interface Handler {
  void handle(EventEnvelope event) throws Exception;
}
