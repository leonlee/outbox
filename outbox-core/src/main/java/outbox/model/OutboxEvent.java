package outbox.model;

import java.time.Instant;

public record OutboxEvent(
    String eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String tenantId,
    String payloadJson,
    String headersJson,
    int attempts,
    Instant createdAt
) {}
