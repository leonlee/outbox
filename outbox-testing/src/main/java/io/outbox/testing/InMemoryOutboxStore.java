package io.outbox.testing;

import io.outbox.EventEnvelope;
import io.outbox.model.EventStatus;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.JsonCodec;
import io.outbox.spi.OutboxStore;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link OutboxStore} for unit testing without JDBC.
 *
 * <p>All events are stored in a {@link ConcurrentHashMap} keyed by event ID.
 * Supports all outbox store operations including poll, claim, mark transitions,
 * and dead event management.
 *
 * <p>The {@code Connection} parameter is ignored in all methods since no
 * database is involved.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var store = new InMemoryOutboxStore();
 * var txContext = new StubTxContext();
 * var writer = new DefaultOutboxWriter(txContext, store);
 * writer.write(EventEnvelope.ofJson("OrderPlaced", "{}"));
 * assertEquals(1, store.size());
 * }</pre>
 */
public class InMemoryOutboxStore implements OutboxStore {
    private final Map<String, StoredEvent> events = new ConcurrentHashMap<>();

    @Override
    public void insertNew(Connection conn, EventEnvelope event) {
        Instant avail = event.availableAt() != null ? event.availableAt() : event.occurredAt();
        events.put(event.eventId(), new StoredEvent(event, EventStatus.NEW, 0, avail, null));
    }

    @Override
    public int markDone(Connection conn, String eventId) {
        StoredEvent existing = events.get(eventId);
        if (existing == null) return 0;
        events.put(eventId, existing.withStatus(EventStatus.DONE));
        return 1;
    }

    @Override
    public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
        StoredEvent existing = events.get(eventId);
        if (existing == null) return 0;
        events.put(eventId, new StoredEvent(
                existing.envelope, EventStatus.RETRY,
                existing.attempts + 1, nextAt, error));
        return 1;
    }

    @Override
    public int markDead(Connection conn, String eventId, String error) {
        StoredEvent existing = events.get(eventId);
        if (existing == null) return 0;
        events.put(eventId, new StoredEvent(
                existing.envelope, EventStatus.DEAD,
                existing.attempts, existing.availableAt, error));
        return 1;
    }

    @Override
    public int markDeferred(Connection conn, String eventId, Instant nextAt) {
        // Same rule as the JDBC stores: a settled event is never brought back, and the last error
        // stays for whoever inspects the row. Checked inside the atomic update, as the JDBC
        // UPDATE's WHERE clause is: a separate read could see NEW, lose to a concurrent markDone,
        // and then write its stale snapshot back over DONE.
        boolean[] deferred = {false};
        events.computeIfPresent(eventId, (id, existing) -> {
            if (existing.status == EventStatus.DONE || existing.status == EventStatus.DEAD) {
                return existing;
            }
            deferred[0] = true;
            return new StoredEvent(existing.envelope, EventStatus.NEW, existing.attempts, nextAt, existing.error);
        });
        return deferred[0] ? 1 : 0;
    }

    @Override
    public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
        Instant cutoff = skipRecent != null ? now.minus(skipRecent) : now;
        return events.values().stream()
                .filter(e -> e.status == EventStatus.NEW || e.status == EventStatus.RETRY)
                .filter(e -> e.availableAt == null || !e.availableAt.isAfter(now))
                .filter(e -> !e.envelope.occurredAt().isAfter(cutoff))
                .sorted(Comparator.comparing(e -> e.envelope.occurredAt()))
                .limit(limit)
                .map(this::toOutboxEvent)
                .toList();
    }

    /**
     * Delegates to {@link #pollPending} — no locking at all, which is why
     * {@code supportsClaimLocking()} is left at {@code false}. Two pollers sharing one of these
     * would both get the same rows.
     */
    @Override
    public List<OutboxEvent> claimPending(
            Connection conn, String ownerId, Instant now,
            Instant lockExpiry, Duration skipRecent, int limit) {
        return pollPending(conn, now, skipRecent, limit);
    }

    @Override
    public List<OutboxEvent> queryDead(Connection conn, String eventType, String aggregateType, int limit) {
        return events.values().stream()
                .filter(e -> e.status == EventStatus.DEAD)
                .filter(e -> eventType == null || e.envelope.eventType().equals(eventType))
                .filter(e -> aggregateType == null || e.envelope.aggregateType().equals(aggregateType))
                .sorted(Comparator.comparing(e -> e.envelope.occurredAt()))
                .limit(limit)
                .map(this::toOutboxEvent)
                .toList();
    }

    @Override
    public int replayDead(Connection conn, String eventId) {
        StoredEvent existing = events.get(eventId);
        if (existing == null || existing.status != EventStatus.DEAD) return 0;
        events.put(eventId, new StoredEvent(
                existing.envelope, EventStatus.NEW, 0, null, null));
        return 1;
    }

    @Override
    public int countDead(Connection conn, String eventType) {
        return (int) events.values().stream()
                .filter(e -> e.status == EventStatus.DEAD)
                .filter(e -> eventType == null || e.envelope.eventType().equals(eventType))
                .count();
    }

    /**
     * Returns the total number of stored events.
     *
     * @return event count
     */
    public int size() {
        return events.size();
    }

    /**
     * Clears all stored events.
     */
    public void clear() {
        events.clear();
    }

    /**
     * Returns all stored events as a list.
     *
     * @return list of all events
     */
    public List<OutboxEvent> all() {
        return events.values().stream()
                .sorted(Comparator.comparing(e -> e.envelope.occurredAt()))
                .map(this::toOutboxEvent)
                .toList();
    }

    /**
     * Returns the status of a specific event.
     *
     * @param eventId the event ID
     * @return the event status, or {@code null} if not found
     */
    public EventStatus statusOf(String eventId) {
        StoredEvent e = events.get(eventId);
        return e == null ? null : e.status;
    }

    private OutboxEvent toOutboxEvent(StoredEvent e) {
        String headersJson = e.envelope.headers().isEmpty()
                ? null
                : JsonCodec.getDefault().toJson(e.envelope.headers());
        return new OutboxEvent(
                e.envelope.eventId(),
                e.envelope.eventType(),
                e.envelope.aggregateType(),
                e.envelope.aggregateId(),
                e.envelope.tenantId(),
                e.envelope.payloadJson(),
                headersJson,
                e.attempts,
                e.envelope.occurredAt(),
                e.availableAt != null ? e.availableAt : e.envelope.availableAt()
        );
    }

    private record StoredEvent(
            EventEnvelope envelope,
            EventStatus status,
            int attempts,
            Instant availableAt,
            String error
    ) {
        StoredEvent withStatus(EventStatus newStatus) {
            return new StoredEvent(envelope, newStatus, attempts, availableAt, error);
        }
    }
}
