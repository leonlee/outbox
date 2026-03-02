package io.outbox.testing;

import io.outbox.spi.TxContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link TxContext} stub for unit testing outbox code without a database.
 *
 * <p>By default the transaction is active. Callbacks registered via
 * {@link #afterCommit(Runnable)} and {@link #afterRollback(Runnable)} are
 * captured and can be triggered manually with {@link #runAfterCommit()} and
 * {@link #runAfterRollback()}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var txContext = new StubTxContext();
 * var writer = new DefaultOutboxWriter(txContext, store);
 * writer.write(EventEnvelope.ofJson("OrderPlaced", "{}"));
 * txContext.runAfterCommit(); // triggers afterCommit hooks
 * }</pre>
 */
public class StubTxContext implements TxContext {
    private boolean active;
    private final List<Runnable> afterCommitCallbacks = new ArrayList<>();
    private final List<Runnable> afterRollbackCallbacks = new ArrayList<>();

    /**
     * Creates a stub with an active transaction.
     */
    public StubTxContext() {
        this(true);
    }

    /**
     * Creates a stub with the specified transaction state.
     *
     * @param active whether the transaction is active
     */
    public StubTxContext(boolean active) {
        this.active = active;
    }

    @Override
    public boolean isTransactionActive() {
        return active;
    }

    @Override
    public Connection currentConnection() {
        return null;
    }

    @Override
    public void afterCommit(Runnable callback) {
        afterCommitCallbacks.add(callback);
    }

    @Override
    public void afterRollback(Runnable callback) {
        afterRollbackCallbacks.add(callback);
    }

    /**
     * Sets whether the transaction is active.
     *
     * @param active the new transaction state
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Runs all registered afterCommit callbacks and clears the list.
     */
    public void runAfterCommit() {
        List<Runnable> callbacks = new ArrayList<>(afterCommitCallbacks);
        afterCommitCallbacks.clear();
        for (Runnable callback : callbacks) {
            callback.run();
        }
    }

    /**
     * Runs all registered afterRollback callbacks and clears the list.
     */
    public void runAfterRollback() {
        List<Runnable> callbacks = new ArrayList<>(afterRollbackCallbacks);
        afterRollbackCallbacks.clear();
        for (Runnable callback : callbacks) {
            callback.run();
        }
    }

    /**
     * Returns the number of pending afterCommit callbacks.
     *
     * @return callback count
     */
    public int afterCommitCount() {
        return afterCommitCallbacks.size();
    }

    /**
     * Returns the number of pending afterRollback callbacks.
     *
     * @return callback count
     */
    public int afterRollbackCount() {
        return afterRollbackCallbacks.size();
    }
}
