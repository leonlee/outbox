package io.outbox.poller;

import io.outbox.EventEnvelope;
import io.outbox.dispatch.QueuedEvent.ClaimLease;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.JsonCodec;
import io.outbox.spi.OutboxStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxPollerTest {

    /**
     * outbox-core carries no codec of its own, and without one every row fails to decode and is
     * quietly marked DEAD instead of dispatched — which would make these tests pass vacuously.
     */
    @BeforeEach
    void installCodec() {
        JsonCodec.setDefault(new JsonCodec() {
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
        JsonCodec.resetDefault();
    }

    private static Connection dummyConnection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> null);
    }

    private static final ConnectionProvider CP = OutboxPollerTest::dummyConnection;

    private static OutboxEvent row(String eventId) {
        return new OutboxEvent(eventId, "test.event", "Agg", "agg-1", null,
                "{}", "{}", 0, Instant.EPOCH, null);
    }

    /**
     * Two overlapping {@link OutboxPoller#poll()} calls must not swap each other's lease.
     *
     * <p>{@code poll()} is public and the scheduler is not the only caller, so two claims can be in
     * flight at once. Each hands its rows to the handler along with the lease they were claimed
     * under; a handler releasing that lease later would otherwise clear a generation belonging to
     * the other batch, un-claiming a row that is still being delivered.
     *
     * <p>The first claim is held open until the second has claimed and dispatched, which is exactly
     * the window a lease kept on the poller gets overwritten in.
     */
    @Test
    void concurrentPollsKeepTheirOwnLease() throws Exception {
        CountDownLatch firstClaimEntered = new CountDownLatch(1);
        CountDownLatch secondPollDone = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger leaseStamps = new AtomicInteger();

        OutboxStore store = new StubStore() {
            @Override
            public Instant leaseTimestamp(Instant now) {
                // Distinct per claim, so a lease can be traced back to the claim that took it.
                return Instant.EPOCH.plusMillis(leaseStamps.incrementAndGet());
            }

            @Override
            public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                                  Instant lockExpiry, Duration skipRecent, int limit) {
                if (claims.incrementAndGet() == 1) {
                    firstClaimEntered.countDown();
                    awaitOrFail(secondPollDone);
                    return List.of(row("first"));
                }
                return List.of(row("second"));
            }
        };

        Map<String, ClaimLease> leaseByEvent = new ConcurrentHashMap<>();
        OutboxPollerHandler handler = new OutboxPollerHandler() {
            @Override
            public boolean handle(EventEnvelope event, int attempts) {
                throw new AssertionError("the lease-carrying overload must be the one called");
            }

            @Override
            public boolean handle(EventEnvelope event, int attempts, ClaimLease claimLease) {
                leaseByEvent.put(event.eventId(), claimLease);
                return true;
            }
        };

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler(handler)
                .claimLocking("owner-1", Duration.ofMinutes(5))
                .build()) {

            Thread first = new Thread(poller::poll, "first-poll");
            first.start();
            assertTrue(firstClaimEntered.await(5, TimeUnit.SECONDS), "first claim never started");

            poller.poll();          // second claim takes a fresh lease while the first is still open
            secondPollDone.countDown();
            first.join(5_000);
        }

        ClaimLease firstLease = leaseByEvent.get("first");
        ClaimLease secondLease = leaseByEvent.get("second");
        assertEquals(2, leaseByEvent.size(), "both rows should have been dispatched");
        assertNotEquals(firstLease, secondLease, "each claim takes its own lease");
        assertEquals(Instant.EPOCH.plusMillis(1), firstLease.claimedAt(),
                "the first batch must carry the lease of the claim that produced it, "
                        + "not whichever one the later claim installed");
        assertEquals(Instant.EPOCH.plusMillis(2), secondLease.claimedAt());
        assertEquals("owner-1", firstLease.owner());
    }

    /** Without claim locking there is no lease to hand out, and the handler must see that. */
    @Test
    void pollingWithoutClaimLockingPassesNoLease() {
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
                return List.of(row("plain"));
            }
        };

        ClaimLease[] seen = {new ClaimLease("stale", Instant.EPOCH)};
        OutboxPollerHandler handler = new OutboxPollerHandler() {
            @Override
            public boolean handle(EventEnvelope event, int attempts) {
                throw new AssertionError("the lease-carrying overload must be the one called");
            }

            @Override
            public boolean handle(EventEnvelope event, int attempts, ClaimLease claimLease) {
                seen[0] = claimLease;
                return true;
            }
        };

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler(handler)
                .build()) {
            poller.poll();
        }

        assertNull(seen[0], "no claim locking means no lease");
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the other poll");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Every method the poller does not exercise in these tests is left unimplemented on purpose. */
    private abstract static class StubStore implements OutboxStore {
        @Override
        public void insertNew(Connection conn, EventEnvelope event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markDone(Connection conn, String eventId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markDead(Connection conn, String eventId, String error) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
