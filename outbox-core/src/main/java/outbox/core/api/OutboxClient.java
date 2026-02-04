package outbox.core.api;

public interface OutboxClient {
  String publish(EventEnvelope event);
}
