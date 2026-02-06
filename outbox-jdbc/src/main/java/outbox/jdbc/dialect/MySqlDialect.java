package outbox.jdbc.dialect;

import java.util.List;

/**
 * MySQL dialect. Also compatible with TiDB.
 */
public final class MySqlDialect extends AbstractDialect {

  @Override
  public String name() {
    return "mysql";
  }

  @Override
  public List<String> jdbcUrlPrefixes() {
    return List.of("jdbc:mysql:", "jdbc:tidb:");
  }
}
