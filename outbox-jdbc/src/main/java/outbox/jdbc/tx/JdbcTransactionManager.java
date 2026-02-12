package outbox.jdbc.tx;

import outbox.spi.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Lightweight transaction manager for manual JDBC usage. Obtains a connection,
 * disables auto-commit, and binds it to a {@link ThreadLocalTxContext}.
 *
 * <p>Use via try-with-resources on the returned {@link Transaction}:
 * <pre>{@code
 * try (var tx = txManager.begin()) {
 *     writer.write(event);
 *     tx.commit();
 * }
 * }</pre>
 *
 * @see ThreadLocalTxContext
 */
public final class JdbcTransactionManager {
  private final ConnectionProvider connectionProvider;
  private final ThreadLocalTxContext txContext;

  public JdbcTransactionManager(ConnectionProvider connectionProvider, ThreadLocalTxContext txContext) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.txContext = Objects.requireNonNull(txContext, "txContext");
  }

  /**
   * Begins a new transaction by obtaining a connection and binding it to the thread context.
   *
   * @return a new {@link Transaction} handle (use with try-with-resources)
   * @throws SQLException if a connection cannot be obtained
   */
  public Transaction begin() throws SQLException {
    Connection connection = connectionProvider.getConnection();
    connection.setAutoCommit(false);
    txContext.bind(connection);
    return new Transaction(connection, txContext);
  }

  /**
   * An active transaction handle. Supports explicit {@link #commit()} and {@link #rollback()}.
   * If neither is called, {@link #close()} triggers a rollback automatically.
   */
  public static final class Transaction implements AutoCloseable {
    private final Connection connection;
    private final ThreadLocalTxContext txContext;
    private boolean completed;

    private Transaction(Connection connection, ThreadLocalTxContext txContext) {
      this.connection = connection;
      this.txContext = txContext;
    }

    public void commit() throws SQLException {
      if (completed) {
        return;
      }
      boolean committed = false;
      try {
        connection.commit();
        committed = true;
      } catch (SQLException e) {
        safeRollback();
        throw e;
      } finally {
        finalizeTx(committed);
      }
    }

    public void rollback() throws SQLException {
      if (completed) {
        return;
      }
      try {
        connection.rollback();
      } finally {
        finalizeTx(false);
      }
    }

    @Override
    public void close() throws SQLException {
      if (!completed) {
        rollback();
      }
    }

    private void finalizeTx(boolean committed) throws SQLException {
      RuntimeException callbackException = null;
      try {
        if (committed) {
          txContext.clearAfterCommit();
        } else {
          txContext.clearAfterRollback();
        }
      } catch (RuntimeException e) {
        callbackException = e;
      } finally {
        completed = true;
        try {
          connection.setAutoCommit(true);
        } catch (SQLException e) {
          if (callbackException != null) callbackException.addSuppressed(e);
        } finally {
          connection.close();
        }
      }
      if (callbackException != null) {
        throw callbackException;
      }
    }

    private void safeRollback() {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
    }
  }
}
