package outbox.jdbc;

import java.util.List;

/**
 * H2 event store. Primarily for testing.
 *
 * <p>Uses the default subquery-based two-phase claim from {@link AbstractJdbcEventStore}.
 */
public final class H2EventStore extends AbstractJdbcEventStore {

  public H2EventStore() {
    super();
  }

  public H2EventStore(String tableName) {
    super(tableName);
  }

  @Override
  public String name() {
    return "h2";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:h2:");
  }
}
