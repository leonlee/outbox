package outbox.jdbc.dialect;

import java.util.List;

/**
 * H2 dialect. Primarily for testing.
 */
public final class H2Dialect extends AbstractDialect {

  @Override
  public String name() {
    return "h2";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:h2:");
  }
}
