package outbox.jdbc.purge;

/**
 * PostgreSQL age-based purger.
 *
 * <p>Uses the default subquery-based purge from {@link AbstractJdbcAgeBasedPurger}.
 */
public final class PostgresAgeBasedPurger extends AbstractJdbcAgeBasedPurger {

  public PostgresAgeBasedPurger() {
    super();
  }

  public PostgresAgeBasedPurger(String tableName) {
    super(tableName);
  }
}
