package outbox.jdbc.tx;

import outbox.spi.TxContext;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TxContext} implementation that stores transaction state in a {@link ThreadLocal}.
 *
 * <p>Designed for manual JDBC transaction management. Use with
 * {@link JdbcTransactionManager} which handles binding, commit/rollback callbacks,
 * and cleanup automatically.
 *
 * @see JdbcTransactionManager
 * @see TxContext
 */
public final class ThreadLocalTxContext implements TxContext {
  private final ThreadLocal<TxState> state = new ThreadLocal<>();

  @Override
  public boolean isTransactionActive() {
    return state.get() != null;
  }

  @Override
  public Connection currentConnection() {
    TxState current = state.get();
    if (current == null) {
      throw new IllegalStateException("No active transaction");
    }
    return current.connection;
  }

  @Override
  public void afterCommit(Runnable callback) {
    TxState current = state.get();
    if (current == null) {
      throw new IllegalStateException("No active transaction");
    }
    current.afterCommit.add(callback);
  }

  @Override
  public void afterRollback(Runnable callback) {
    TxState current = state.get();
    if (current == null) {
      throw new IllegalStateException("No active transaction");
    }
    current.afterRollback.add(callback);
  }

  void bind(Connection connection) {
    if (state.get() != null) {
      throw new IllegalStateException("Transaction already active");
    }
    state.set(new TxState(connection));
  }

  void clearAfterCommit() {
    TxState current = state.get();
    if (current == null) {
      return;
    }
    try {
      RuntimeException first = null;
      for (Runnable callback : current.afterCommit) {
        try {
          callback.run();
        } catch (RuntimeException e) {
          if (first == null) first = e;
          else first.addSuppressed(e);
        }
      }
      if (first != null) throw first;
    } finally {
      state.remove();
    }
  }

  void clearAfterRollback() {
    TxState current = state.get();
    if (current == null) {
      return;
    }
    try {
      RuntimeException first = null;
      for (Runnable callback : current.afterRollback) {
        try {
          callback.run();
        } catch (RuntimeException e) {
          if (first == null) first = e;
          else first.addSuppressed(e);
        }
      }
      if (first != null) throw first;
    } finally {
      state.remove();
    }
  }

  private static final class TxState {
    private final Connection connection;
    private final List<Runnable> afterCommit = new ArrayList<>();
    private final List<Runnable> afterRollback = new ArrayList<>();

    private TxState(Connection connection) {
      this.connection = connection;
    }
  }
}
