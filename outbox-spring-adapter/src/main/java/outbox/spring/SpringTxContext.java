package outbox.spring;

import outbox.spi.TxContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;

/**
 * {@link TxContext} implementation that bridges to Spring's transaction infrastructure
 * via {@link TransactionSynchronizationManager}.
 *
 * <p>Connections are obtained through {@link DataSourceUtils} to participate in
 * Spring-managed transactions. After-commit and after-rollback callbacks are
 * registered as {@link TransactionSynchronization} instances.
 *
 * @see TxContext
 */
public final class SpringTxContext implements TxContext {
  private final DataSource dataSource;

  public SpringTxContext(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public boolean isTransactionActive() {
    return TransactionSynchronizationManager.isActualTransactionActive();
  }

  @Override
  public Connection currentConnection() {
    if (!isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    return DataSourceUtils.getConnection(dataSource);
  }

  @Override
  public void afterCommit(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    requireSynchronizationActive("afterCommit");
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        callback.run();
      }
    });
  }

  @Override
  public void afterRollback(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    requireSynchronizationActive("afterRollback");
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) {
          callback.run();
        }
      }
    });
  }

  private void requireSynchronizationActive(String operation) {
    if (!isTransactionActive()) {
      throw new IllegalStateException("No active transaction");
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Transaction synchronization is not active; cannot register " + operation + " callback");
    }
  }
}
