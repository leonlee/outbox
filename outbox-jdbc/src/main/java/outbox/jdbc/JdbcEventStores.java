package outbox.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for JDBC event stores with auto-detection support.
 *
 * <p>Event stores are loaded via {@link ServiceLoader} from
 * {@code META-INF/services/outbox.jdbc.AbstractJdbcEventStore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Auto-detect from DataSource
 * AbstractJdbcEventStore store = JdbcEventStores.detect(dataSource);
 *
 * // Auto-detect from JDBC URL
 * AbstractJdbcEventStore store = JdbcEventStores.detect("jdbc:mysql://localhost/mydb");
 *
 * // Get by name
 * AbstractJdbcEventStore store = JdbcEventStores.get("postgresql");
 *
 * // List all registered event stores
 * List<AbstractJdbcEventStore> all = JdbcEventStores.all();
 * }</pre>
 */
public final class JdbcEventStores {

  private static final List<AbstractJdbcEventStore> STORES;
  private static final Map<String, AbstractJdbcEventStore> BY_NAME = new ConcurrentHashMap<>();

  static {
    STORES = ServiceLoader.load(AbstractJdbcEventStore.class)
        .stream()
        .map(ServiceLoader.Provider::get)
        .toList();

    for (AbstractJdbcEventStore store : STORES) {
      BY_NAME.put(store.name().toLowerCase(), store);
    }
  }

  private JdbcEventStores() {
  }

  /**
   * Returns all registered event stores.
   */
  public static List<AbstractJdbcEventStore> all() {
    return STORES;
  }

  /**
   * Gets an event store by name.
   *
   * @param name event store name (case-insensitive)
   * @return the event store
   * @throws IllegalArgumentException if no event store found
   */
  public static AbstractJdbcEventStore get(String name) {
    AbstractJdbcEventStore store = BY_NAME.get(name.toLowerCase());
    if (store == null) {
      throw new IllegalArgumentException("Unknown event store: " + name +
          ". Available: " + BY_NAME.keySet());
    }
    return store;
  }

  /**
   * Auto-detects event store from a DataSource.
   *
   * @param dataSource the data source
   * @return detected event store
   * @throws IllegalStateException if detection fails or no matching event store
   */
  public static AbstractJdbcEventStore detect(DataSource dataSource) {
    try (Connection conn = dataSource.getConnection()) {
      String url = conn.getMetaData().getURL();
      return detect(url);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to detect event store from DataSource", e);
    }
  }

  /**
   * Auto-detects event store from a JDBC URL.
   *
   * @param jdbcUrl the JDBC URL
   * @return detected event store
   * @throws IllegalArgumentException if no matching event store found
   */
  public static AbstractJdbcEventStore detect(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isEmpty()) {
      throw new IllegalArgumentException("JDBC URL cannot be null or empty");
    }

    for (AbstractJdbcEventStore store : STORES) {
      for (String prefix : store.jdbcUrlPrefixes()) {
        if (jdbcUrl.toLowerCase().startsWith(prefix.toLowerCase())) {
          return store;
        }
      }
    }

    throw new IllegalArgumentException("No event store found for JDBC URL: " + jdbcUrl +
        ". Supported prefixes: " + allPrefixes());
  }

  private static List<String> allPrefixes() {
    return STORES.stream()
        .flatMap(s -> s.jdbcUrlPrefixes().stream())
        .toList();
  }
}
