package io.outbox.dispatch;

import io.outbox.EventEnvelope;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.OutboxStore;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal OutboxStore stub for unit tests that don't need real JDBC.
 */
class StubOutboxStore implements OutboxStore {
    final AtomicInteger markDoneCount = new AtomicInteger();
    final AtomicInteger markRetryCount = new AtomicInteger();
    final AtomicInteger markDeadCount = new AtomicInteger();
    final AtomicInteger markDeferredCount = new AtomicInteger();
    final AtomicReference<Instant> lastRetryNextAt = new AtomicReference<>();
    final AtomicReference<Instant> lastDeferredNextAt = new AtomicReference<>();

    @Override
    public void insertNew(Connection conn, EventEnvelope event) {
    }

    @Override
    public int markDone(Connection conn, String eventId) {
        markDoneCount.incrementAndGet();
        return 1;
    }

    @Override
    public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
        markRetryCount.incrementAndGet();
        lastRetryNextAt.set(nextAt);
        return 1;
    }

    @Override
    public int markDead(Connection conn, String eventId, String error) {
        markDeadCount.incrementAndGet();
        return 1;
    }

    @Override
    public int markDeferred(Connection conn, String eventId, Instant nextAt) {
        markDeferredCount.incrementAndGet();
        lastDeferredNextAt.set(nextAt);
        return 1;
    }

    @Override
    public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
        return List.of();
    }
}
