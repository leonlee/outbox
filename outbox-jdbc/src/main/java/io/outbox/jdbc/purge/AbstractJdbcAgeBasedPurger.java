package io.outbox.jdbc.purge;

import io.outbox.jdbc.JdbcTemplate;
import io.outbox.jdbc.TableNames;
import io.outbox.spi.EventPurger;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Base JDBC age-based purger that deletes <em>all</em> events older than a
 * cutoff, regardless of status.
 *
 * <p>Designed for CDC (Change Data Capture) scenarios where no dispatcher or
 * poller marks events as DONE. Events are consumed externally (e.g. via
 * Debezium reading the WAL/binlog), so the only safe criterion for cleanup
 * is age.
 *
 * <p>Default SQL uses a subquery-based {@code DELETE} that works for H2 and
 * PostgreSQL. MySQL overrides with {@code DELETE ... ORDER BY ... LIMIT}.
 *
 * @see H2AgeBasedPurger
 * @see MySqlAgeBasedPurger
 * @see PostgresAgeBasedPurger
 */
public abstract class AbstractJdbcAgeBasedPurger implements EventPurger {
    protected static final String DEFAULT_TABLE = TableNames.DEFAULT_TABLE;

    private final String tableName;

    protected AbstractJdbcAgeBasedPurger() {
        this(DEFAULT_TABLE);
    }

    protected AbstractJdbcAgeBasedPurger(String tableName) {
        this.tableName = TableNames.validate(tableName);
    }

    protected String tableName() {
        return tableName;
    }

    /**
     * Deletes all events older than {@code before}, up to {@code limit} rows.
     *
     * <p>Default implementation uses a subquery to limit the batch size, which
     * works for H2 and PostgreSQL. MySQL overrides with {@code DELETE ... ORDER BY ... LIMIT}.
     */
    @Override
    public int purge(Connection conn, Instant before, int limit) {
        String sql = "DELETE FROM " + tableName() + " WHERE event_id IN (" +
                "SELECT event_id FROM " + tableName() +
                " WHERE created_at < ?" +
                " ORDER BY created_at LIMIT ?)";
        return JdbcTemplate.update(conn, sql, Timestamp.from(before), limit);
    }
}
