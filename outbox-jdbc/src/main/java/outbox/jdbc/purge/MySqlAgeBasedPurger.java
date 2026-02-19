package outbox.jdbc.purge;

import outbox.jdbc.JdbcTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * MySQL age-based purger. Also compatible with TiDB.
 *
 * <p>Overrides with {@code DELETE ... ORDER BY ... LIMIT}, which MySQL supports
 * natively and avoids the self-referencing subquery.
 */
public final class MySqlAgeBasedPurger extends AbstractJdbcAgeBasedPurger {

  public MySqlAgeBasedPurger() {
    super();
  }

  public MySqlAgeBasedPurger(String tableName) {
    super(tableName);
  }

  @Override
  public int purge(Connection conn, Instant before, int limit) {
    String sql = "DELETE FROM " + tableName() +
        " WHERE created_at < ?" +
        " ORDER BY created_at LIMIT ?";
    return JdbcTemplate.update(conn, sql, Timestamp.from(before), limit);
  }
}
