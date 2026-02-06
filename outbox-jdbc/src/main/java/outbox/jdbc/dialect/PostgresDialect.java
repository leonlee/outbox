package outbox.jdbc.dialect;

import java.util.List;

/**
 * PostgreSQL dialect.
 */
public final class PostgresDialect extends AbstractDialect {

  @Override
  public String name() {
    return "postgresql";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:postgresql:");
  }
}
