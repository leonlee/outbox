package io.outbox.jdbc.tx;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.outbox.jdbc.DataSourceConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTransactionManagerTest {
    private JdbcDataSource dataSource;
    private ThreadLocalTxContext txContext;
    private JdbcTransactionManager txManager;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:txmgr_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        txContext = new ThreadLocalTxContext();
        txManager = new JdbcTransactionManager(
                new DataSourceConnectionProvider(dataSource), txContext);

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(
                    "CREATE TABLE test_data (id INT PRIMARY KEY, val VARCHAR(100))");
        }
    }

    @Test
    void constructorRejectsNullConnectionProvider() {
        assertThrows(NullPointerException.class, () ->
                new JdbcTransactionManager(null, txContext));
    }

    @Test
    void constructorRejectsNullTxContext() {
        assertThrows(NullPointerException.class, () ->
                new JdbcTransactionManager(new DataSourceConnectionProvider(dataSource), null));
    }

    @Test
    void commitPersistsData() throws Exception {
        try (var tx = txManager.begin()) {
            Connection conn = txContext.currentConnection();
            conn.createStatement().execute("INSERT INTO test_data (id, val) VALUES (1, 'hello')");
            tx.commit();
        }

        assertEquals("hello", queryValue(1));
    }

    @Test
    void rollbackDiscardsData() throws Exception {
        try (var tx = txManager.begin()) {
            Connection conn = txContext.currentConnection();
            conn.createStatement().execute("INSERT INTO test_data (id, val) VALUES (1, 'hello')");
            tx.rollback();
        }

        assertNull(queryValue(1));
    }

    @Test
    void closeAutoRollbacksWhenNotCommitted() throws Exception {
        try (var tx = txManager.begin()) {
            Connection conn = txContext.currentConnection();
            conn.createStatement().execute("INSERT INTO test_data (id, val) VALUES (1, 'hello')");
            // neither commit() nor rollback() — close() should auto-rollback
        }

        assertNull(queryValue(1));
    }

    @Test
    void doubleCommitIsNoOp() throws Exception {
        try (var tx = txManager.begin()) {
            Connection conn = txContext.currentConnection();
            conn.createStatement().execute("INSERT INTO test_data (id, val) VALUES (1, 'hello')");
            tx.commit();
            tx.commit(); // second call should be a no-op
        }

        assertEquals("hello", queryValue(1));
    }

    @Test
    void doubleRollbackIsNoOp() throws Exception {
        try (var tx = txManager.begin()) {
            Connection conn = txContext.currentConnection();
            conn.createStatement().execute("INSERT INTO test_data (id, val) VALUES (1, 'hello')");
            tx.rollback();
            tx.rollback(); // second call should be a no-op
        }

        assertNull(queryValue(1));
    }

    @Test
    void commitRunsAfterCommitCallbacks() throws Exception {
        AtomicBoolean called = new AtomicBoolean();

        try (var tx = txManager.begin()) {
            txContext.afterCommit(() -> called.set(true));
            tx.commit();
        }

        assertTrue(called.get());
    }

    @Test
    void rollbackRunsAfterRollbackCallbacks() throws Exception {
        AtomicBoolean called = new AtomicBoolean();

        try (var tx = txManager.begin()) {
            txContext.afterRollback(() -> called.set(true));
            tx.rollback();
        }

        assertTrue(called.get());
    }

    @Test
    void commitDoesNotRunAfterRollbackCallbacks() throws Exception {
        AtomicBoolean rollbackCalled = new AtomicBoolean();

        try (var tx = txManager.begin()) {
            txContext.afterRollback(() -> rollbackCalled.set(true));
            tx.commit();
        }

        assertFalse(rollbackCalled.get());
    }

    @Test
    void rollbackDoesNotRunAfterCommitCallbacks() throws Exception {
        AtomicBoolean commitCalled = new AtomicBoolean();

        try (var tx = txManager.begin()) {
            txContext.afterCommit(() -> commitCalled.set(true));
            tx.rollback();
        }

        assertFalse(commitCalled.get());
    }

    @Test
    void afterCommitCallbackExceptionPropagates() throws Exception {
        RuntimeException expected = new RuntimeException("callback boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (var tx = txManager.begin()) {
                txContext.afterCommit(() -> {
                    throw expected;
                });
                tx.commit();
            }
        });

        assertSame(expected, thrown);
    }

    @Test
    void afterCommitMultipleCallbackExceptionsAreSuppressed() throws Exception {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (var tx = txManager.begin()) {
                txContext.afterCommit(() -> {
                    throw first;
                });
                txContext.afterCommit(() -> {
                    throw second;
                });
                tx.commit();
            }
        });

        assertSame(first, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(second, thrown.getSuppressed()[0]);
    }

    @Test
    void txContextUnboundAfterCommit() throws Exception {
        try (var tx = txManager.begin()) {
            assertTrue(txContext.isTransactionActive());
            tx.commit();
        }

        assertFalse(txContext.isTransactionActive());
    }

    @Test
    void txContextUnboundAfterRollback() throws Exception {
        try (var tx = txManager.begin()) {
            assertTrue(txContext.isTransactionActive());
            tx.rollback();
        }

        assertFalse(txContext.isTransactionActive());
    }

    @Test
    void txContextUnboundAfterAutoRollbackOnClose() throws Exception {
        try (var tx = txManager.begin()) {
            assertTrue(txContext.isTransactionActive());
        }

        assertFalse(txContext.isTransactionActive());
    }

    @Test
    void beginBindsConnectionToTxContext() throws Exception {
        try (var tx = txManager.begin()) {
            assertTrue(txContext.isTransactionActive());
            assertNotNull(txContext.currentConnection());
            tx.commit();
        }
    }

    @Test
    void commitFailureTriggersRollbackThenRethrows() throws Exception {
        AtomicInteger insertCount = new AtomicInteger();

        try (var tx = txManager.begin()) {
            Connection conn = txContext.currentConnection();
            conn.createStatement().execute("INSERT INTO test_data (id, val) VALUES (1, 'hello')");
            insertCount.set(1);

            // Force a commit failure by closing the connection before commit
            conn.close();

            assertThrows(SQLException.class, tx::commit);
        }

        // The connection was closed before commit, so data should not persist
        // (commit failure triggers safeRollback, but connection is already closed)
    }

    @Test
    void connectionIsClosedAfterCommit() throws Exception {
        Connection captured;
        try (var tx = txManager.begin()) {
            captured = txContext.currentConnection();
            tx.commit();
        }

        assertTrue(captured.isClosed());
    }

    @Test
    void connectionIsClosedAfterRollback() throws Exception {
        Connection captured;
        try (var tx = txManager.begin()) {
            captured = txContext.currentConnection();
            tx.rollback();
        }

        assertTrue(captured.isClosed());
    }

    private String queryValue(int id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT val FROM test_data WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
