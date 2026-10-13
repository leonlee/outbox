package io.outbox.poller;

import io.outbox.EventEnvelope;
import io.outbox.dispatch.QueuedEvent.ClaimLease;
import io.outbox.model.OutboxEvent;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.JsonCodec;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * Capacity is read once per poll. Reading it again inside the fetch could find it gone, and
     * the empty batch that produced reset the lag gauge to zero while rows were still waiting.
     */
    @Test
    void capacityGoneMidPollDoesNotReportZeroLag() {
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
                return List.of(row("waiting"));
            }
        };
        AtomicInteger capacityReads = new AtomicInteger();
        OutboxPollerHandler handler = new OutboxPollerHandler() {
            @Override
            public boolean handle(EventEnvelope event, int attempts) {
                return true;
            }

            @Override
            public int availableCapacity() {
                // Room for one at the first look, none after: the queue filled in between.
                return capacityReads.getAndIncrement() == 0 ? 1 : 0;
            }
        };
        List<Long> lags = new CopyOnWriteArrayList<>();
        MetricsExporter metrics = (MetricsExporter) Proxy.newProxyInstance(
                MetricsExporter.class.getClassLoader(),
                new Class<?>[]{MetricsExporter.class},
                (proxy, method, args) -> {
                    if ("recordOldestLagMs".equals(method.getName())) {
                        lags.add((Long) args[0]);
                    }
                    return null;
                });

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler(handler)
                .metrics(metrics)
                .build()) {
            poller.poll();
        }

        assertFalse(lags.contains(0L), "a row has been waiting since the epoch; lag readings were " + lags);
    }

    /**
     * A broken connection fails the rollback too. The rollback's error must not replace the claim
     * failure, which is the one that says what went wrong.
     */
    @Test
    void aFailedRollbackDoesNotHideTheClaimFailure() {
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                                  Instant lockExpiry, Duration skipRecent, int limit) {
                throw new IllegalStateException("claim failed");
            }
        };
        ConnectionProvider brokenRollback = () -> (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("rollback".equals(method.getName())) {
                        throw new SQLException("connection closed");
                    }
                    return null;
                });
        List<Throwable> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getThrown() != null) {
                    logged.add(record.getThrown());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(OutboxPoller.class.getName());
        logger.addHandler(capture);
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(brokenRollback)
                .outboxStore(store)
                .handler((event, attempts) -> true)
                .claimLocking("owner-1", Duration.ofMinutes(5))
                .build()) {
            poller.poll();
        } finally {
            logger.removeHandler(capture);
        }

        assertEquals(1, logged.size(), "one failure logged: " + logged);
        Throwable failure = logged.get(0);
        assertEquals("claim failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("connection closed", failure.getSuppressed()[0].getMessage());
    }

    /**
     * No codec is a configuration fault, not bad rows: the poll must fail before claiming anything
     * and mark nothing DEAD, so the same rows deliver once a codec is installed.
     */
    @Test
    void missingCodecFailsThePollWithoutClaimingOrKillingRows() {
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger dead = new AtomicInteger();
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                                  Instant lockExpiry, Duration skipRecent, int limit) {
                claims.incrementAndGet();
                return List.of(row("valid"));
            }

            @Override
            public int markDead(Connection conn, String eventId, String error) {
                dead.incrementAndGet();
                return 1;
            }
        };
        List<String> handled = new CopyOnWriteArrayList<>();

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler((event, attempts) -> handled.add(event.eventId()))
                .claimLocking("owner-1", Duration.ofMinutes(5))
                .build()) {
            JsonCodec.resetDefault(); // outbox-core has no SPI codec, so discovery fails
            poller.poll();
            assertEquals(0, claims.get(), "nothing may be claimed without a way to decode it");
            assertEquals(0, dead.get());

            installCodec();
            poller.poll();
        }

        assertEquals(List.of("valid"), handled);
        assertEquals(0, dead.get());
    }

    /** Undecodable headers are a bad row, and that one still goes DEAD while the rest deliver. */
    @Test
    void malformedHeadersStillMarkTheRowDead() {
        JsonCodec.setDefault(new JsonCodec() {
            @Override
            public String toJson(Object obj) {
                return "{}";
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T fromJson(String json, Class<T> type) {
                if ("not json".equals(json)) {
                    throw new IllegalArgumentException("malformed");
                }
                return (T) new java.util.LinkedHashMap<String, String>();
            }
        });
        List<String> deadIds = new CopyOnWriteArrayList<>();
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
                return List.of(new OutboxEvent("bad", "test.event", "Agg", "agg-1", null,
                        "{}", "not json", 0, Instant.EPOCH, null), row("good"));
            }

            @Override
            public int markDead(Connection conn, String eventId, String error) {
                deadIds.add(eventId);
                return 1;
            }
        };
        List<String> handled = new CopyOnWriteArrayList<>();

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler((event, attempts) -> handled.add(event.eventId()))
                .build()) {
            poller.poll();
        }

        assertEquals(List.of("bad"), deadIds);
        assertEquals(List.of("good"), handled);
    }

    /** The poller's own DEAD write for an undecodable row is fenced on the batch's exact lease. */
    @Test
    void decodeFailureDeadWriteIsFencedOnTheBatchLease() {
        JsonCodec.setDefault(new JsonCodec() {
            @Override
            public String toJson(Object obj) {
                return "{}";
            }

            @Override
            public <T> T fromJson(String json, Class<T> type) {
                throw new IllegalArgumentException("malformed");
            }
        });
        List<String> fences = new CopyOnWriteArrayList<>();
        List<Instant> claims = new CopyOnWriteArrayList<>();
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                                  Instant lockExpiry, Duration skipRecent, int limit) {
                claims.add(leaseTimestamp(now));
                return List.of(row("bad"));
            }

            @Override
            public int markDead(Connection conn, String eventId, String error, String claimOwner, Instant claimedAt) {
                fences.add(claimOwner + "@" + claimedAt);
                return 1;
            }
        };

        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler((event, attempts) -> true)
                .claimLocking("owner-1", Duration.ofMinutes(5))
                .build()) {
            poller.poll();
        }

        assertEquals(List.of("owner-1@" + claims.get(0)), fences);
    }

    /**
     * A retry can schedule next_attempt before created_at (another node's clock behind, or a
     * business occurredAt in the future). Rebuilding the envelope with that time used to trip
     * EventEnvelope's "availableAt must not be before occurredAt" check, and the decode-failure path
     * marked a perfectly valid row DEAD. Both the unleased and the leased paths must deliver it.
     */
    @Test
    void aRetryScheduledBeforeCreatedAtIsDeliveredNotMarkedDead() {
        Instant createdAt = Instant.parse("2026-10-13T00:00:02Z");
        OutboxEvent retried = new OutboxEvent("early", "test.event", "Agg", "agg-1", null,
                "{}", "{}", 1, createdAt, createdAt.minusMillis(1500));
        for (boolean leased : new boolean[]{false, true}) {
            List<String> deaths = new CopyOnWriteArrayList<>();
            List<String> delivered = new CopyOnWriteArrayList<>();
            OutboxStore store = new StubStore() {
                @Override
                public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
                    return List.of(retried);
                }

                @Override
                public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                                      Instant lockExpiry, Duration skipRecent, int limit) {
                    return List.of(retried);
                }

                @Override
                public int markDead(Connection conn, String eventId, String error) {
                    deaths.add(error);
                    return 1;
                }

                @Override
                public int markDead(Connection conn, String eventId, String error, String claimOwner, Instant claimedAt) {
                    deaths.add(error);
                    return 1;
                }
            };
            OutboxPoller.Builder builder = OutboxPoller.builder()
                    .connectionProvider(CP)
                    .outboxStore(store)
                    .handler((event, attempts) -> delivered.add(event.eventId()));
            if (leased) {
                builder.claimLocking("owner-1", Duration.ofMinutes(5));
            }
            try (OutboxPoller poller = builder.build()) {
                poller.poll();
            }
            assertEquals(List.of(), deaths, "leased=" + leased);
            assertEquals(List.of("early"), delivered, "leased=" + leased);
        }
    }

    /**
     * PostgreSQL's UPDATE … RETURNING does not return rows in the subquery's ORDER BY. The poller
     * hands rows over oldest first anyway, so the lag gauge reads the oldest row and back-pressure
     * leaves the newest ones claimed.
     */
    @Test
    void claimedRowsAreHandedOverOldestFirstWhateverOrderTheStoreReturns() {
        Instant t = Instant.parse("2026-10-13T00:00:00Z");
        List<OutboxEvent> unordered = List.of(
                new OutboxEvent("b", "test.event", "Agg", "agg-1", null, "{}", "{}", 0, t.plusSeconds(2), null),
                new OutboxEvent("c", "test.event", "Agg", "agg-1", null, "{}", "{}", 0, t.plusSeconds(3), null),
                new OutboxEvent("a", "test.event", "Agg", "agg-1", null, "{}", "{}", 0, t.plusSeconds(1), null));
        OutboxStore store = new StubStore() {
            @Override
            public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                                  Instant lockExpiry, Duration skipRecent, int limit) {
                return unordered;
            }
        };
        List<String> handed = new CopyOnWriteArrayList<>();
        try (OutboxPoller poller = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(store)
                .handler((event, attempts) -> handed.add(event.eventId()))
                .claimLocking("owner-1", Duration.ofMinutes(5))
                .build()) {
            poller.poll();
        }
        assertEquals(List.of("a", "b", "c"), handed);
    }

    /** A locking store that predates fencing is refused up front rather than failing every fenced write. */
    @Test
    void claimLockingRejectsALockingStoreWithoutFencing() {
        OutboxStore legacy = new StubStore() {
            @Override
            public boolean supportsClaimLocking() {
                return true;
            }
        };
        OutboxPoller.Builder builder = OutboxPoller.builder()
                .connectionProvider(CP)
                .outboxStore(legacy)
                .handler((event, attempts) -> true)
                .claimLocking("owner-1", Duration.ofMinutes(5));

        assertThrows(IllegalArgumentException.class, builder::build);
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
