package outbox.core.tx;

import java.sql.Connection;

public interface TxContext {
  boolean isTransactionActive();

  Connection currentConnection();

  void afterCommit(Runnable callback);

  void afterRollback(Runnable callback);
}
