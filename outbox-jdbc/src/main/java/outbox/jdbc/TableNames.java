package outbox.jdbc;

import java.util.Objects;

/**
 * Shared table name validation for JDBC outbox components.
 */
public final class TableNames {
  public static final String DEFAULT_TABLE = "outbox_event";
  private static final String TABLE_NAME_PATTERN = "[a-zA-Z_][a-zA-Z0-9_]*";

  private TableNames() {}

  public static String validate(String tableName) {
    Objects.requireNonNull(tableName, "tableName");
    if (!tableName.matches(TABLE_NAME_PATTERN)) {
      throw new IllegalArgumentException("Invalid table name: " + tableName);
    }
    return tableName;
  }
}
