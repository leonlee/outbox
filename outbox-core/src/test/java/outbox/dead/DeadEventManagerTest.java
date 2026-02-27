package outbox.dead;

import org.junit.jupiter.api.Test;
import outbox.EventEnvelope;
import outbox.model.OutboxEvent;
import outbox.spi.ConnectionProvider;
import outbox.spi.OutboxStore;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadEventManagerTest {

    private static Connection dummyConnection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> null);
    }

    private static ConnectionProvider dummyProvider() {
        return DeadEventManagerTest::dummyConnection;
    }

    private static ConnectionProvider failingProvider() {
        return () -> {
            throw new SQLException("connection failed");
        };
    }

    private static OutboxEvent deadEvent(String eventId) {
        return new OutboxEvent(eventId, "TestEvent", "Agg", "agg-1", null,
                "{}", null, 3, Instant.now(), null);
    }

    @Test
    void queryDelegatesAndReturns() {
        OutboxEvent event = deadEvent("evt-1");
        OutboxStore store = stubStore(List.of(event), 1, 1);
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        List<OutboxEvent> result = manager.query(null, null, 10);

        assertEquals(1, result.size());
        assertEquals("evt-1", result.get(0).eventId());
    }

    @Test
    void replayResetsToNew() {
        OutboxStore store = stubStore(List.of(), 1, 0);
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertTrue(manager.replay("evt-1"));
    }

    @Test
    void replayReturnsFalseForNonDead() {
        OutboxStore store = stubStore(List.of(), 0, 0);
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertFalse(manager.replay("evt-non-dead"));
    }

    @Test
    void replayAllProcessesBatches() {
        OutboxEvent e1 = deadEvent("evt-1");
        OutboxEvent e2 = deadEvent("evt-2");
        OutboxEvent e3 = deadEvent("evt-3");

        // First query returns 2 (full batch), second returns 1 (partial = stop)
        List<List<OutboxEvent>> batches = new ArrayList<>();
        batches.add(List.of(e1, e2));
        batches.add(List.of(e3));

        OutboxStore store = new StubOutboxStore() {
            int queryCall = 0;

            @Override
            public List<OutboxEvent> queryDead(Connection conn, String eventType,
                                               String aggregateType, int limit) {
                return queryCall < batches.size() ? batches.get(queryCall++) : List.of();
            }

            @Override
            public int replayDead(Connection conn, String eventId) {
                return 1;
            }
        };

        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);
        int total = manager.replayAll(null, null, 2);

        assertEquals(3, total);
    }

    @Test
    void replayAllRejectsBatchSizeZero() {
        OutboxStore store = stubStore(List.of(), 0, 0);
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertThrows(IllegalArgumentException.class, () ->
                manager.replayAll(null, null, 0));
    }

    @Test
    void replayAllRejectsNegativeBatchSize() {
        OutboxStore store = stubStore(List.of(), 0, 0);
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertThrows(IllegalArgumentException.class, () ->
                manager.replayAll(null, null, -1));
    }

    @Test
    void countDelegates() {
        OutboxStore store = stubStore(List.of(), 0, 5);
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertEquals(5, manager.count(null));
    }

    @Test
    void constructorRejectsNull() {
        OutboxStore store = stubStore(List.of(), 0, 0);

        assertThrows(NullPointerException.class, () ->
                new DeadEventManager(null, store));
        assertThrows(NullPointerException.class, () ->
                new DeadEventManager(dummyProvider(), null));
    }

    @Test
    void queryThrowsOnSqlException() {
        OutboxStore store = stubStore(List.of(), 0, 0);
        DeadEventManager manager = new DeadEventManager(failingProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.query(null, null, 10));
    }

    @Test
    void replayThrowsOnSqlException() {
        OutboxStore store = stubStore(List.of(), 0, 0);
        DeadEventManager manager = new DeadEventManager(failingProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.replay("evt-1"));
    }

    @Test
    void countThrowsOnSqlException() {
        OutboxStore store = stubStore(List.of(), 0, 0);
        DeadEventManager manager = new DeadEventManager(failingProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.count(null));
    }

    // ── Store-level RuntimeException (e.g. OutboxStoreException) ────

    @Test
    void queryThrowsOnStoreRuntimeException() {
        OutboxStore store = new StubOutboxStore() {
            @Override
            public List<OutboxEvent> queryDead(Connection conn, String eventType,
                                               String aggregateType, int limit) {
                throw new RuntimeException("store failure");
            }
        };
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.query(null, null, 10));
    }

    @Test
    void replayThrowsOnStoreRuntimeException() {
        OutboxStore store = new StubOutboxStore() {
            @Override
            public int replayDead(Connection conn, String eventId) {
                throw new RuntimeException("store failure");
            }
        };
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.replay("evt-1"));
    }

    @Test
    void countThrowsOnStoreRuntimeException() {
        OutboxStore store = new StubOutboxStore() {
            @Override
            public int countDead(Connection conn, String eventType) {
                throw new RuntimeException("store failure");
            }
        };
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.count(null));
    }

    @Test
    void replayAllThrowsOnStoreRuntimeException() {
        OutboxStore store = new StubOutboxStore() {
            @Override
            public List<OutboxEvent> queryDead(Connection conn, String eventType,
                                               String aggregateType, int limit) {
                throw new RuntimeException("store failure");
            }
        };
        DeadEventManager manager = new DeadEventManager(dummyProvider(), store);

        assertThrows(RuntimeException.class, () -> manager.replayAll(null, null, 10));
    }

    private static OutboxStore stubStore(List<OutboxEvent> queryResult,
                                         int replayResult, int countResult) {
        return new StubOutboxStore() {
            @Override
            public List<OutboxEvent> queryDead(Connection conn, String eventType,
                                               String aggregateType, int limit) {
                return queryResult;
            }

            @Override
            public int replayDead(Connection conn, String eventId) {
                return replayResult;
            }

            @Override
            public int countDead(Connection conn, String eventType) {
                return countResult;
            }
        };
    }

    /**
     * Minimal OutboxStore stub satisfying abstract methods; only dead-event methods are used.
     */
    private static abstract class StubOutboxStore implements OutboxStore {
        @Override
        public void insertNew(Connection conn, EventEnvelope event) {
        }

        @Override
        public int markDone(Connection conn, String eventId) {
            return 0;
        }

        @Override
        public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
            return 0;
        }

        @Override
        public int markDead(Connection conn, String eventId, String error) {
            return 0;
        }

        @Override
        public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
            return List.of();
        }
    }
}
