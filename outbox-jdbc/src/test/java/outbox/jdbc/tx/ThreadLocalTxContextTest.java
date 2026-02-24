package outbox.jdbc.tx;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadLocalTxContextTest {

    private JdbcDataSource dataSource;
    private ThreadLocalTxContext txContext;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        txContext = new ThreadLocalTxContext();
    }

    @Test
    void isTransactionActiveReturnsFalseWhenNotBound() {
        assertFalse(txContext.isTransactionActive());
    }

    @Test
    void isTransactionActiveReturnsTrueWhenBound() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            assertTrue(txContext.isTransactionActive());
        }
    }

    @Test
    void currentConnectionThrowsWhenNotBound() {
        assertThrows(IllegalStateException.class, () -> txContext.currentConnection());
    }

    @Test
    void currentConnectionReturnsBoundConnection() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            assertSame(conn, txContext.currentConnection());
        }
    }

    @Test
    void afterCommitCallbacksAreExecutedOnClear() throws SQLException {
        AtomicInteger counter = new AtomicInteger();

        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterCommit(() -> counter.addAndGet(1));
            txContext.afterCommit(() -> counter.addAndGet(10));
            txContext.afterCommit(() -> counter.addAndGet(100));

            assertEquals(0, counter.get());

            txContext.clearAfterCommit();

            assertEquals(111, counter.get());
        }
    }

    @Test
    void afterRollbackCallbacksAreExecutedOnClear() throws SQLException {
        AtomicInteger counter = new AtomicInteger();

        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterRollback(() -> counter.addAndGet(1));
            txContext.afterRollback(() -> counter.addAndGet(10));

            assertEquals(0, counter.get());

            txContext.clearAfterRollback();

            assertEquals(11, counter.get());
        }
    }

    @Test
    void clearAfterCommitDoesNotExecuteRollbackCallbacks() throws SQLException {
        AtomicBoolean commitCalled = new AtomicBoolean();
        AtomicBoolean rollbackCalled = new AtomicBoolean();

        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterCommit(() -> commitCalled.set(true));
            txContext.afterRollback(() -> rollbackCalled.set(true));

            txContext.clearAfterCommit();

            assertTrue(commitCalled.get());
            assertFalse(rollbackCalled.get());
        }
    }

    @Test
    void clearAfterRollbackDoesNotExecuteCommitCallbacks() throws SQLException {
        AtomicBoolean commitCalled = new AtomicBoolean();
        AtomicBoolean rollbackCalled = new AtomicBoolean();

        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterCommit(() -> commitCalled.set(true));
            txContext.afterRollback(() -> rollbackCalled.set(true));

            txContext.clearAfterRollback();

            assertFalse(commitCalled.get());
            assertTrue(rollbackCalled.get());
        }
    }

    @Test
    void clearResetsState() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterCommit(() -> {
            });

            txContext.clearAfterCommit();

            assertFalse(txContext.isTransactionActive());
            assertThrows(IllegalStateException.class, () -> txContext.currentConnection());
        }
    }

    @Test
    void threadIsolation() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean thread1Active = new AtomicBoolean();
        AtomicBoolean thread2Active = new AtomicBoolean();

        Thread t1 = new Thread(() -> {
            try (Connection conn = dataSource.getConnection()) {
                txContext.bind(conn);
                thread1Active.set(txContext.isTransactionActive());
                latch.countDown();
                latch.await();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                latch.countDown();
                latch.await();
                thread2Active.set(txContext.isTransactionActive());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertTrue(thread1Active.get());
        assertFalse(thread2Active.get()); // Different thread, not bound
    }

    @Test
    void callbackExceptionIsPropagated() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterCommit(() -> {
                throw new RuntimeException("callback failed");
            });

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> txContext.clearAfterCommit());

            assertEquals("callback failed", ex.getMessage());
        }
    }

    @Test
    void callbackExceptionDoesNotStopSubsequentCallbacks() throws SQLException {
        AtomicInteger counter = new AtomicInteger();

        try (Connection conn = dataSource.getConnection()) {
            txContext.bind(conn);
            txContext.afterCommit(() -> counter.incrementAndGet());
            txContext.afterCommit(() -> {
                throw new RuntimeException("boom");
            });
            txContext.afterCommit(() -> counter.incrementAndGet());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> txContext.clearAfterCommit());

            assertEquals("boom", ex.getMessage());
            // All callbacks ran: first and third incremented, second threw
            assertEquals(2, counter.get());
            // State is still cleared even after exception
            assertFalse(txContext.isTransactionActive());
        }
    }
}
