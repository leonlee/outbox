package io.outbox.spi;

import java.sql.Connection;

/**
 * Abstracts the transaction lifecycle so outbox operations can participate in
 * the caller's transaction without depending on a specific transaction manager.
 *
 * <p>Implementations: {@link io.outbox.jdbc.tx.ThreadLocalTxContext} (manual JDBC),
 * {@code io.outbox.spring.SpringTxContext} (Spring-managed).
 *
 * @see io.outbox.jdbc.tx.ThreadLocalTxContext
 */
public interface TxContext {

    /**
     * Returns {@code true} if a transaction is currently active on this thread.
     */
    boolean isTransactionActive();

    /**
     * Returns the JDBC connection bound to the current transaction.
     *
     * @throws IllegalStateException if no transaction is active
     */
    Connection currentConnection();

    /**
     * Registers a callback to run after the current transaction commits.
     *
     * @param callback action to execute post-commit
     * @throws IllegalStateException if no transaction is active
     */
    void afterCommit(Runnable callback);

    /**
     * Registers a callback to run after the current transaction rolls back.
     *
     * @param callback action to execute post-rollback
     * @throws IllegalStateException if no transaction is active
     */
    void afterRollback(Runnable callback);
}
