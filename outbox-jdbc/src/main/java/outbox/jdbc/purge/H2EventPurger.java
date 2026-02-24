package outbox.jdbc.purge;

/**
 * H2 event purger. Uses the default subquery-based {@code DELETE}
 * from {@link AbstractJdbcEventPurger}.
 */
public final class H2EventPurger extends AbstractJdbcEventPurger {

    public H2EventPurger() {
        super();
    }

    public H2EventPurger(String tableName) {
        super(tableName);
    }
}
