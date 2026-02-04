package outbox.jdbc;

import outbox.core.tx.ConnectionProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class DataSourceConnectionProvider implements ConnectionProvider {
  private final DataSource dataSource;

  public DataSourceConnectionProvider(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }
}
