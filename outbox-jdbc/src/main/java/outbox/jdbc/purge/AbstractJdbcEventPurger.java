package outbox.jdbc.purge;

import outbox.jdbc.JdbcTemplate;
import outbox.model.EventStatus;
import outbox.spi.EventPurger;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * Base JDBC event purger with default subquery-based SQL that works for H2
 * and PostgreSQL.
 *
 * <p>Subclasses may override {@link #purge} for databases that support more
 * efficient syntax (e.g. MySQL supports {@code DELETE ... ORDER BY ... LIMIT}).
 *
 * @see H2EventPurger
 * @see MySqlEventPurger
 * @see PostgresEventPurger
 */
public abstract class AbstractJdbcEventPurger implements EventPurger {
  protected static final String DEFAULT_TABLE = "outbox_event";

  protected static final String TERMINAL_STATUS_IN =
      "(" + EventStatus.DONE.code() + "," + EventStatus.DEAD.code() + ")";

  private final String tableName;

  protected AbstractJdbcEventPurger() {
    this(DEFAULT_TABLE);
  }

  protected AbstractJdbcEventPurger(String tableName) {
    Objects.requireNonNull(tableName, "tableName");
    if (!tableName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      throw new IllegalArgumentException("Invalid table name: " + tableName);
    }
    this.tableName = tableName;
  }

  protected String tableName() {
    return tableName;
  }

  /**
   * Deletes terminal events older than {@code before}, up to {@code limit} rows.
   *
   * <p>Default implementation uses a subquery to limit the batch size, which
   * works for H2 and PostgreSQL. MySQL overrides with {@code DELETE ... ORDER BY ... LIMIT}.
   */
  @Override
  public int purge(Connection conn, Instant before, int limit) {
    String sql = "DELETE FROM " + tableName() + " WHERE event_id IN (" +
        "SELECT event_id FROM " + tableName() +
        " WHERE status IN " + TERMINAL_STATUS_IN +
        " AND COALESCE(done_at, created_at) < ?" +
        " ORDER BY created_at LIMIT ?)";
    return JdbcTemplate.update(conn, sql, Timestamp.from(before), limit);
  }
}
