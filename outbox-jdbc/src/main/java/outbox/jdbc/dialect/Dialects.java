package outbox.jdbc.dialect;

import outbox.jdbc.spi.Dialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for database dialects with auto-detection support.
 *
 * <p>Dialects are loaded via {@link ServiceLoader} from
 * {@code META-INF/services/outbox.jdbc.spi.Dialect}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Auto-detect from DataSource
 * Dialect dialect = Dialects.detect(dataSource);
 *
 * // Auto-detect from JDBC URL
 * Dialect dialect = Dialects.detect("jdbc:mysql://localhost/mydb");
 *
 * // Get by name
 * Dialect dialect = Dialects.get("postgresql");
 *
 * // List all registered dialects
 * List<Dialect> all = Dialects.all();
 * }</pre>
 */
public final class Dialects {

  private static final List<Dialect> DIALECTS;
  private static final Map<String, Dialect> BY_NAME = new ConcurrentHashMap<>();

  static {
    DIALECTS = ServiceLoader.load(Dialect.class)
        .stream()
        .map(ServiceLoader.Provider::get)
        .toList();

    for (Dialect dialect : DIALECTS) {
      BY_NAME.put(dialect.name().toLowerCase(), dialect);
    }
  }

  private Dialects() {
  }

  /**
   * Returns all registered dialects.
   */
  public static List<Dialect> all() {
    return DIALECTS;
  }

  /**
   * Gets a dialect by name.
   *
   * @param name dialect name (case-insensitive)
   * @return the dialect
   * @throws IllegalArgumentException if no dialect found
   */
  public static Dialect get(String name) {
    Dialect dialect = BY_NAME.get(name.toLowerCase());
    if (dialect == null) {
      throw new IllegalArgumentException("Unknown dialect: " + name +
          ". Available: " + BY_NAME.keySet());
    }
    return dialect;
  }

  /**
   * Auto-detects dialect from a DataSource.
   *
   * @param dataSource the data source
   * @return detected dialect
   * @throws IllegalStateException if detection fails or no matching dialect
   */
  public static Dialect detect(DataSource dataSource) {
    try (Connection conn = dataSource.getConnection()) {
      String url = conn.getMetaData().getURL();
      return detect(url);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to detect dialect from DataSource", e);
    }
  }

  /**
   * Auto-detects dialect from a JDBC URL.
   *
   * @param jdbcUrl the JDBC URL
   * @return detected dialect
   * @throws IllegalArgumentException if no matching dialect found
   */
  public static Dialect detect(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isEmpty()) {
      throw new IllegalArgumentException("JDBC URL cannot be null or empty");
    }

    for (Dialect dialect : DIALECTS) {
      for (String prefix : dialect.jdbcUrlPrefixes()) {
        if (jdbcUrl.startsWith(prefix)) {
          return dialect;
        }
      }
    }

    throw new IllegalArgumentException("No dialect found for JDBC URL: " + jdbcUrl +
        ". Supported prefixes: " + allPrefixes());
  }

  private static List<String> allPrefixes() {
    return DIALECTS.stream()
        .flatMap(d -> d.jdbcUrlPrefixes().stream())
        .toList();
  }
}
