package io.outbox;

import io.outbox.dispatch.OutboxDispatcher;
import io.outbox.dispatch.QueuedEvent;
import io.outbox.model.OutboxEvent;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Poller-only dispatch: {@code hotPathEnabled=false}.
 *
 * @see OutboxDispatcher.Builder#hotPathEnabled(boolean)
 */
class HotPathDisabledTest {

    /** outbox-core ships no codec; without one every polled row fails to decode and is marked DEAD. */
    @BeforeEach
    void installCodec() {
        io.outbox.spi.JsonCodec.setDefault(new io.outbox.spi.JsonCodec() {
            @Override
            public String toJson(Object obj) {
                return "{}";
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T fromJson(String json, Class<T> type) {
                return (T) new java.util.LinkedHashMap<String, String>();
            }
        });
    }

    @AfterEach
    void removeCodec() {
        io.outbox.spi.JsonCodec.resetDefault();
    }

    private static final ConnectionProvider CP = () -> (Connection) java.lang.reflect.Proxy
            .newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> null);

    /** Runs after-commit callbacks inline, which is when a writer hook would fire if one existed. */
    private static final class ImmediateTxContext implements TxContext {
        @Override
        public boolean isTransactionActive() {
            return true;
        }

        @Override
        public Connection currentConnection() {
            try {
                return CP.getConnection();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void afterCommit(Runnable cb) {
            cb.run();
        }

        @Override
        public void afterRollback(Runnable cb) {
        }
    }

    /** Stands in for a real store, so it claims — see the rejection test for one that does not. */
    private static class RecordingStore implements OutboxStore {
        private final List<String> inserted = new ArrayList<>();

        @Override
        public boolean supportsClaimLocking() {
            return true;
        }

        @Override
        public synchronized void insertNew(Connection conn, EventEnvelope event) {
            inserted.add(event.eventId());
        }

        @Override
        public int markDone(Connection conn, String eventId) {
            return 1;
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
            return 1;
        }

        @Override
        public int markDead(Connection conn, String eventId, String error) {
            return 1;
        }

        @Override
        public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skip, int limit) {
            return List.of();
        }
    }

    private static OutboxDispatcher dispatcher(boolean hotPathEnabled, DefaultListenerRegistry registry) {
        return OutboxDispatcher.builder()
                .connectionProvider(CP)
                .outboxStore(new RecordingStore())
                .listenerRegistry(registry)
                .workerCount(1)
                .drainTimeoutMs(1000)
                .hotPathEnabled(hotPathEnabled)
                .build();
    }

    @Test
    void hotEnqueueIsRefusedSoNothingBypassesAClaim() {
        try (var d = dispatcher(false, new DefaultListenerRegistry())) {
            var queued = new QueuedEvent(EventEnvelope.ofJson("T", "{}"), QueuedEvent.Source.HOT, 0);
            assertFalse(d.enqueueHot(queued),
                    "with the hot path off there is no in-memory delivery to accept into");
        }
    }

    @Test
    void coldDeliveryStillWorks() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register("T", event -> {
            latch.countDown();
            return DispatchResult.done();
        });
        try (var d = dispatcher(false, registry)) {
            assertTrue(d.enqueueCold(new QueuedEvent(EventEnvelope.ofJson("T", "{}"),
                    QueuedEvent.Source.COLD, 0)));
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the poller's queue is the only path left");
        }
    }

    /**
     * The writer must not carry a dispatcher hook, so committing writes the row and stops there.
     *
     * <p>Asserted as a difference against the same setup with the hot path on, rather than as a
     * bare "nothing happened": a listener that stays silent proves little on its own — a broken
     * registry or a dead worker looks identical. The control run has to fire for the poller-only
     * run's silence to mean anything.
     */
    @Test
    void committingDoesNotDispatchWhenThereIsNoHotPath() throws Exception {
        assertTrue(dispatchedOnCommit(true), "control: with the hot path on, commit dispatches");
        assertFalse(dispatchedOnCommit(false),
                "with the hot path off, commit must only persist — delivery waits for a claim");
    }

    private boolean dispatchedOnCommit(boolean hotPathEnabled) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        var registry = new DefaultListenerRegistry().register("T", event -> {
            latch.countDown();
            return DispatchResult.done();
        });
        var store = new RecordingStore();
        try (Outbox outbox = Outbox.multiNode()
                .connectionProvider(CP)
                .txContext(new ImmediateTxContext())
                .outboxStore(store)
                .listenerRegistry(registry)
                .workerCount(1)
                // Long enough that the poller cannot be what delivers within the wait below.
                .intervalMs(600_000)
                .deferStart(true)
                .claimLocking("owner", Duration.ofMinutes(5))
                .hotPathEnabled(hotPathEnabled)
                .build()) {
            outbox.writer().write(EventEnvelope.ofJson("T", "{}"));
            assertEquals(1, store.inserted.size(), "the row is written either way");
            return latch.await(1, TimeUnit.SECONDS);
        }
    }

    /**
     * Poller-only needs claim locking, and single-node has none — so the combination is refused.
     *
     * <p>An unlocked poll returns any row that is still PENDING, and a row stays PENDING for as
     * long as its listener runs. Every listener slower than the poll interval would therefore be
     * handed the same event again, and again, until it finished — measured at 4 deliveries of one
     * row with a 400ms listener on a 100ms interval. Multi-node is correct on a single node too,
     * so the fix for anyone hitting this is one line of configuration.
     */
    @Test
    void singleNodeRefusesPollerOnlyBecauseItTakesNoClaims() {
        var e = assertThrows(IllegalStateException.class, () -> Outbox.singleNode()
                .connectionProvider(CP)
                .txContext(new ImmediateTxContext())
                .outboxStore(new RecordingStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .hotPathEnabled(false)
                .build());
        assertTrue(e.getMessage().contains("multiNode()"), "the message must say what to use: " + e.getMessage());
    }

    /**
     * A listener slower than the poll interval must still be delivered once.
     *
     * <p>Ordered mode is poller-only and unlocked, so it had exactly the defect described above —
     * this is the pre-existing case that cannot simply be refused. The dispatcher now declines to
     * queue a second copy of an event it is already running, which closes it without a claim.
     */
    @Test
    void aListenerSlowerThanThePollIntervalIsNotRedelivered() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        var registry = new DefaultListenerRegistry().register("Agg", "Slow", event -> {
            deliveries.incrementAndGet();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });
        var store = new StuckRowStore();
        Outbox outbox = Outbox.ordered()
                .connectionProvider(CP)
                .txContext(new ImmediateTxContext())
                .outboxStore(store)
                .listenerRegistry(registry)
                .intervalMs(100)
                .build();
        try (outbox) {
            Thread.sleep(2000);
        }
        assertEquals(1, deliveries.get(),
                "one row, delivered once — the poller saw it " + store.polls.get() + " times");
    }

    /** One row that keeps coming back from an unlocked poll until something marks it DONE. */
    private static final class StuckRowStore implements OutboxStore {
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicInteger polls = new AtomicInteger();

        @Override
        public void insertNew(Connection conn, EventEnvelope event) {
        }

        @Override
        public int markDone(Connection conn, String eventId) {
            done.set(true);
            return 1;
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
            return 1;
        }

        @Override
        public int markDead(Connection conn, String eventId, String error) {
            return 1;
        }

        @Override
        public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skip, int limit) {
            polls.incrementAndGet();
            return done.get() ? List.of()
                    : List.of(new OutboxEvent("stuck-1", "Slow", "Agg", "a", null, "{}", "{}", 0,
                            Instant.now().minusSeconds(60), null));
        }
    }

    /**
     * A configured claim is not the same as a claim the store honours.
     *
     * <p>{@code OutboxStore.claimPending} falls through to an unlocked {@code pollPending} by
     * default, so multi-node with a store that never overrode it delivers exactly the re-delivery
     * single-node is refused for — while passing every other check. Poller-only asks the store to
     * say it locks.
     */
    @Test
    void pollerOnlyRefusesAStoreThatDoesNotActuallyClaim() {
        var e = assertThrows(IllegalStateException.class, () -> Outbox.multiNode()
                .connectionProvider(CP)
                .txContext(new ImmediateTxContext())
                .outboxStore(new RecordingStore() {
                    @Override
                    public boolean supportsClaimLocking() {
                        return false;               // SPI default: claimPending -> pollPending
                    }
                })
                .listenerRegistry(new DefaultListenerRegistry())
                .claimLocking("owner", Duration.ofMinutes(5))
                .hotPathEnabled(false)
                .build());
        assertTrue(e.getMessage().contains("supportsClaimLocking"), e.getMessage());
    }

    /** The same wiring is accepted once the store says its claim locks. */
    @Test
    void pollerOnlyAcceptsAStoreThatClaims() {
        try (Outbox outbox = Outbox.multiNode()
                .connectionProvider(CP)
                .txContext(new ImmediateTxContext())
                .outboxStore(new RecordingStore())
                .listenerRegistry(new DefaultListenerRegistry())
                .claimLocking("owner", Duration.ofMinutes(5))
                .intervalMs(600_000)
                .deferStart(true)
                .hotPathEnabled(false)
                .build()) {
            assertNotNull(outbox.writer());
        }
    }

    /**
     * A backlog is not re-queued while it waits its turn.
     *
     * <p>The single-row case above only covers the row a worker already holds. Everything else the
     * poll returned is sitting in the cold queue having acquired nothing, so a guard that asks only
     * "is this running" lets the next poll queue all of it again — and the one after that, and so
     * on. Measured on these four rows before the queued set existed: 1, 3, 6 and 9 deliveries.
     */
    @Test
    void aBacklogWaitingItsTurnIsNotQueuedAgain() throws Exception {
        Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        var registry = new DefaultListenerRegistry().register("Agg", "Slow", event -> {
            deliveries.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DispatchResult.done();
        });

        var store = new BacklogStore(4);
        Outbox outbox = Outbox.ordered()
                .connectionProvider(CP)
                .txContext(new ImmediateTxContext())
                .outboxStore(store)
                .listenerRegistry(registry)
                .intervalMs(50)
                .build();
        try (outbox) {
            Thread.sleep(2500);
        }

        assertEquals(4, deliveries.size(), "every row delivered");
        deliveries.forEach((id, count) ->
                assertEquals(1, count.get(), id + " was delivered " + count.get() + " times"));
    }

    /** Rows that keep coming back from an unlocked poll until each is marked DONE. */
    private static final class BacklogStore implements OutboxStore {
        private final int rows;
        private final Set<String> done = ConcurrentHashMap.newKeySet();

        BacklogStore(int rows) {
            this.rows = rows;
        }

        @Override
        public void insertNew(Connection conn, EventEnvelope event) {
        }

        @Override
        public int markDone(Connection conn, String eventId) {
            done.add(eventId);
            return 1;
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
            return 1;
        }

        @Override
        public int markDead(Connection conn, String eventId, String error) {
            return 1;
        }

        @Override
        public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skip, int limit) {
            List<OutboxEvent> pending = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                String id = "row-" + i;
                if (!done.contains(id)) {
                    pending.add(new OutboxEvent(id, "Slow", "Agg", "a", null, "{}", "{}", 0,
                            Instant.now().minusSeconds(60), null));
                }
            }
            return pending;
        }
    }
}
