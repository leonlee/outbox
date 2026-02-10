package outbox.jdbc;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * MySQL event purger. Also compatible with TiDB.
 *
 * <p>Overrides with {@code DELETE ... ORDER BY ... LIMIT}, which MySQL supports
 * natively and avoids the self-referencing subquery.
 */
public final class MySqlEventPurger extends AbstractJdbcEventPurger {

  public MySqlEventPurger() {
    super();
  }

  public MySqlEventPurger(String tableName) {
    super(tableName);
  }

  @Override
  public int purge(Connection conn, Instant before, int limit) {
    String sql = "DELETE FROM " + tableName() +
        " WHERE status IN " + TERMINAL_STATUS_IN +
        " AND COALESCE(done_at, created_at) < ?" +
        " ORDER BY created_at LIMIT ?";
    return JdbcTemplate.update(conn, sql, Timestamp.from(before), limit);
  }
}
