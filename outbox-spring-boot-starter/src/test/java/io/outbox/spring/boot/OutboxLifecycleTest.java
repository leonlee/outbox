package io.outbox.spring.boot;

import io.outbox.EventEnvelope;
import io.outbox.Outbox;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxLifecycleTest {

    private static final io.outbox.spi.ConnectionProvider STUB_CP = () -> {
        throw new SQLException("stub");
    };
    private static final TxContext STUB_TX = new TxContext() {
        @Override
        public boolean isTransactionActive() {
            return false;
        }

        @Override
        public Connection currentConnection() {
            throw new IllegalStateException("stub");
        }

        @Override
        public void afterCommit(Runnable action) {
        }

        @Override
        public void afterRollback(Runnable action) {
        }
    };
    private static final OutboxStore STUB_STORE = new OutboxStore() {
        @Override
        public void insertNew(Connection c, EventEnvelope e) {
        }

        @Override
        public int markDone(Connection c, String id) {
            return 0;
        }

        @Override
        public int markRetry(Connection c, String id, Instant a, String e) {
            return 0;
        }

        @Override
        public int markDead(Connection c, String id, String e) {
            return 0;
        }

        @Override
        public List<io.outbox.model.OutboxEvent> pollPending(Connection c, Instant n, Duration s, int l) {
            return List.of();
        }
    };

    @Test
    void startDelegatesAndSetsRunning() {
        Outbox outbox = Outbox.singleNode()
                .connectionProvider(STUB_CP).txContext(STUB_TX)
                .outboxStore(STUB_STORE).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).intervalMs(60_000)
                .deferStart(true)
                .build();

        var lifecycle = new OutboxLifecycle(outbox);
        assertFalse(lifecycle.isRunning());

        lifecycle.start();
        assertTrue(lifecycle.isRunning());

        lifecycle.stop();
        assertFalse(lifecycle.isRunning());

        outbox.close();
    }
}
