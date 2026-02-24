package outbox.jdbc.purge;

/**
 * H2 age-based purger. Uses the default subquery-based {@code DELETE}
 * from {@link AbstractJdbcAgeBasedPurger}.
 */
public final class H2AgeBasedPurger extends AbstractJdbcAgeBasedPurger {

    public H2AgeBasedPurger() {
        super();
    }

    public H2AgeBasedPurger(String tableName) {
        super(tableName);
    }
}
