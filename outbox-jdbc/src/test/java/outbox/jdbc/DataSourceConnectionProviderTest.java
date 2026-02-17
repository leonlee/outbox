package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceConnectionProviderTest {

  @Test
  void nullDataSourceThrows() {
    assertThrows(NullPointerException.class, () ->
        new DataSourceConnectionProvider(null));
  }

  @Test
  void delegatesToDataSource() throws SQLException {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:dscp_test;DB_CLOSE_DELAY=-1");

    DataSourceConnectionProvider provider = new DataSourceConnectionProvider(ds);

    try (Connection conn = provider.getConnection()) {
      assertNotNull(conn);
      assertFalse(conn.isClosed());
    }
  }
}
