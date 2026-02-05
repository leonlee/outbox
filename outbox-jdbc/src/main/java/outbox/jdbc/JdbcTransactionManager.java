package outbox.jdbc;

import outbox.spi.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcTransactionManager {
  private final ConnectionProvider connectionProvider;
  private final ThreadLocalTxContext txContext;

  public JdbcTransactionManager(ConnectionProvider connectionProvider, ThreadLocalTxContext txContext) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.txContext = Objects.requireNonNull(txContext, "txContext");
  }

  public Transaction begin() throws SQLException {
    Connection connection = connectionProvider.getConnection();
    connection.setAutoCommit(false);
    txContext.bind(connection);
    return new Transaction(connection, txContext);
  }

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
        connection.setAutoCommit(true);
        connection.close();
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
