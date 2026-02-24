package outbox.model;

import java.time.Instant;

/**
 * Read-only record representing a persisted outbox event row, as returned by
 * the poller and dispatcher when reading from the database.
 *
 * @see outbox.spi.OutboxStore#pollPending
 * @see outbox.spi.OutboxStore#claimPending
 */
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
) {
}
