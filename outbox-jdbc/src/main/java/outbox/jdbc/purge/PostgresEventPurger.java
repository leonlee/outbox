package outbox.jdbc.purge;

/**
 * PostgreSQL event purger.
 *
 * <p>Uses the default subquery-based purge from {@link AbstractJdbcEventPurger}.
 */
public final class PostgresEventPurger extends AbstractJdbcEventPurger {

    public PostgresEventPurger() {
        super();
    }

    public PostgresEventPurger(String tableName) {
        super(tableName);
    }
}
