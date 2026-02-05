package outbox.model;

import java.time.Instant;

public final class OutboxEvent {
  private final String eventId;
  private final String eventType;
  private final String aggregateType;
  private final String aggregateId;
  private final String tenantId;
  private final String payloadJson;
  private final String headersJson;
  private final int attempts;
  private final Instant createdAt;

  public OutboxEvent(
      String eventId,
      String eventType,
      String aggregateType,
      String aggregateId,
      String tenantId,
      String payloadJson,
      String headersJson,
      int attempts,
      Instant createdAt
  ) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.tenantId = tenantId;
    this.payloadJson = payloadJson;
    this.headersJson = headersJson;
    this.attempts = attempts;
    this.createdAt = createdAt;
  }

  public String eventId() {
    return eventId;
  }

  public String eventType() {
    return eventType;
  }

  public String aggregateType() {
    return aggregateType;
  }

  public String aggregateId() {
    return aggregateId;
  }

  public String tenantId() {
    return tenantId;
  }

  public String payloadJson() {
    return payloadJson;
  }

  public String headersJson() {
    return headersJson;
  }

  public int attempts() {
    return attempts;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
