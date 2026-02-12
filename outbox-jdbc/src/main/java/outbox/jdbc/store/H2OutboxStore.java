package outbox.jdbc.store;

import java.util.List;

/**
 * H2 outbox store. Primarily for testing.
 *
 * <p>Uses the default subquery-based two-phase claim from {@link AbstractJdbcOutboxStore}.
 */
public final class H2OutboxStore extends AbstractJdbcOutboxStore {

  public H2OutboxStore() {
    super();
  }

  public H2OutboxStore(String tableName) {
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
