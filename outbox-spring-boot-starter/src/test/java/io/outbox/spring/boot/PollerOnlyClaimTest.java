package io.outbox.spring.boot;

import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.Outbox;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.JsonCodec;
import io.outbox.spi.OutboxStore;
import io.outbox.model.OutboxEvent;
import io.outbox.jdbc.store.H2OutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim semantics behind poller-only delivery, exercised as real SQL against H2.
 *
 * <p>These are the guarantees that make it safe to drop the writer stamp: if a claim is genuinely
 * exclusive for the length of its lease, nothing else is needed to keep two deliveries apart.
 */
class PollerOnlyClaimTest {

    private static final String URL = "jdbc:h2:mem:poller_only_claim;DB_CLOSE_DELAY=-1";
    private static final Duration LOCK_TIMEOUT = Duration.ofMinutes(5);

    /** No writer owner: poller-only inserts leave the ownership columns alone. */
    private final H2OutboxStore store = new H2OutboxStore("outbox_event", null);

    /**
     * The same store, declaring the claim locking poller-only requires.
     *
     * <p>{@code H2OutboxStore} reports {@code false} for a good reason: H2 has no
     * {@code FOR UPDATE SKIP LOCKED}, so two concurrent pollers could win the same row. Nothing
     * here runs two pollers — these tests are about what a single claim does to a row, and H2 is
     * the only engine a unit test has. Declared through a wrapper rather than by relaxing the
     * store, so production keeps its honest answer.
     */
    private final OutboxStore claimingStore = new OutboxStore() {
        @Override
        public boolean supportsClaimLocking() {
            return true;
        }

        @Override
        public void insertNew(Connection conn, EventEnvelope event) {
            store.insertNew(conn, event);
        }

        @Override
        public int markDone(Connection conn, String eventId) {
            return store.markDone(conn, eventId);
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
            return store.markRetry(conn, eventId, nextAt, error);
        }

        @Override
        public int markDead(Connection conn, String eventId, String error) {
            return store.markDead(conn, eventId, error);
        }

        @Override
        public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skip, int limit) {
            return store.pollPending(conn, now, skip, limit);
        }

        @Override
        public List<OutboxEvent> claimPending(Connection conn, String ownerId, Instant now,
                                              Instant lockExpiry, Duration skip, int limit) {
            return store.claimPending(conn, ownerId, now, lockExpiry, skip, limit);
        }

        @Override
        public int releaseClaim(Connection conn, String eventId, String owner, Instant claimedAt) {
            return store.releaseClaim(conn, eventId, owner, claimedAt);
        }

        @Override
        public Instant leaseTimestamp(Instant now) {
            return store.leaseTimestamp(now);
        }
    };

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(URL);
        conn.setAutoCommit(true);
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS outbox_event");
            st.execute("""
                    CREATE TABLE outbox_event (
                      event_id VARCHAR(36) PRIMARY KEY,
                      event_type VARCHAR(128) NOT NULL,
                      aggregate_type VARCHAR(64),
                      aggregate_id VARCHAR(128),
                      tenant_id VARCHAR(64),
                      payload CLOB NOT NULL,
                      headers CLOB,
                      status TINYINT NOT NULL,
                      attempts INT NOT NULL DEFAULT 0,
                      available_at TIMESTAMP NOT NULL,
                      created_at TIMESTAMP NOT NULL,
                      done_at TIMESTAMP,
                      last_error CLOB,
                      locked_by VARCHAR(128),
                      locked_at TIMESTAMP
                    )""");
        }
    }

    private String insertOne() {
        EventEnvelope event = EventEnvelope.ofJson("T", "{}");
        store.insertNew(conn, event);
        return event.eventId();
    }

    private List<OutboxEvent> claim(String owner, Instant at) {
        return store.claimPending(conn, owner, at, at.minus(LOCK_TIMEOUT), Duration.ZERO, 10);
    }

    private String lockedBy(String eventId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT locked_by FROM outbox_event WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    @Test
    void insertsCarryNoOwnershipWithoutAWriterStamp() throws SQLException {
        String eventId = insertOne();
        try (var ps = conn.prepareStatement(
                "SELECT locked_by, locked_at FROM outbox_event WHERE event_id=?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getString("locked_by"), "poller-only rows are inserted unowned");
                assertNull(rs.getTimestamp("locked_at"), "and unleased");
            }
        }
    }

    /**
     * The whole point of the mode: a second poll arriving before the first delivery finishes must
     * come back empty, so only one copy of the event is ever in flight.
     */
    @Test
    void aSecondPollBeforeCompletionClaimsNothing() {
        String eventId = insertOne();
        Instant t = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);

        List<OutboxEvent> first = claim("node-a", t);
        assertEquals(1, first.size());
        assertEquals(eventId, first.get(0).eventId());

        assertEquals(List.of(), claim("node-a", t.plusSeconds(1)),
                "the same node polling again must not pick up a row it is still delivering");
    }

    @Test
    void anotherNodeCannotClaimAnActiveLease() throws SQLException {
        String eventId = insertOne();
        Instant t = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);

        assertEquals(1, claim("node-a", t).size());
        assertEquals(List.of(), claim("node-b", t.plusSeconds(1)),
                "an active lease belongs to exactly one node");
        assertEquals("node-a", lockedBy(eventId), "and the loser must not have overwritten it");
    }

    @Test
    void theRowBecomesClaimableOnceTheLeaseExpires() throws SQLException {
        String eventId = insertOne();
        Instant t = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        assertEquals(1, claim("node-a", t).size());

        // A node that dies mid-delivery leaves its lease behind; at-least-once depends on someone
        // else picking the row up once that lease is older than the lock timeout.
        Instant afterExpiry = t.plus(LOCK_TIMEOUT).plusSeconds(1);
        assertEquals(1, claim("node-b", afterExpiry).size(), "an expired lease is takeover-able");
        assertEquals("node-b", lockedBy(eventId));
    }

    /**
     * End-to-end: poller-only delivery of a listener slower than the poll interval, once.
     *
     * <p>The case the claim exists for. While the listener runs the row is still PENDING, so an
     * unlocked poll would keep handing it out — 4 deliveries of one row, measured, in the modes
     * that do not claim. Here the claim holds it, and the assertion is on the count rather than
     * on "it was delivered", because delivering is the easy half.
     */
    @Test
    void aSlowListenerIsDeliveredOnceUnderPollerOnlyDelivery() throws Exception {
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
        try {
            insertOne();
            AtomicInteger deliveries = new AtomicInteger();
            var registry = new DefaultListenerRegistry().register("T", event -> {
                deliveries.incrementAndGet();
                try {
                    Thread.sleep(400);      // four poll intervals
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return DispatchResult.done();
            });

            Outbox outbox = Outbox.multiNode()
                    .connectionProvider(() -> DriverManager.getConnection(URL))
                    .txContext(new NoOpTxContext())
                    .outboxStore(claimingStore)
                    .listenerRegistry(registry)
                    .workerCount(2)
                    .intervalMs(100)
                    .hotPathEnabled(false)
                    .claimLocking("node-a", LOCK_TIMEOUT)
                    .build();
            try (outbox) {
                Thread.sleep(1500);
            }

            assertEquals(1, deliveries.get(), "one row, one delivery");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM outbox_event WHERE status=1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "and it ended DONE");
            }
        } finally {
            JsonCodec.resetDefault();
        }
    }

    /** Nothing writes during this test; the row is inserted through the store directly. */
    private static final class NoOpTxContext implements io.outbox.spi.TxContext {
        @Override
        public boolean isTransactionActive() {
            return false;
        }

        @Override
        public Connection currentConnection() {
            throw new IllegalStateException("not used");
        }

        @Override
        public void afterCommit(Runnable cb) {
        }

        @Override
        public void afterRollback(Runnable cb) {
        }
    }

    /** A delivered row is terminal, so no later poll should see it whatever the lease says. */
    @Test
    void aCompletedRowIsNeverClaimedAgain() {
        insertOne();
        Instant t = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        List<OutboxEvent> claimed = claim("node-a", t);
        assertEquals(1, claimed.size());

        store.markDone(conn, claimed.get(0).eventId());
        assertEquals(List.of(), claim("node-a", t.plus(LOCK_TIMEOUT).plusSeconds(1)),
                "DONE outranks an expired lease");
    }
}
