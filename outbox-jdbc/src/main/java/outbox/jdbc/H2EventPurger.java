package outbox.jdbc;

/**
 * H2 event purger. Uses the default {@code DELETE ... ORDER BY ... LIMIT}
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
