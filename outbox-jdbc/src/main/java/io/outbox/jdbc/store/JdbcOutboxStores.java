package io.outbox.jdbc.store;

import io.outbox.util.JsonCodec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for JDBC outbox stores with auto-detection support.
 *
 * <p>Event stores are loaded via {@link ServiceLoader} from
 * {@code META-INF/services/io.outbox.jdbc.store.AbstractJdbcOutboxStore}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Auto-detect from DataSource
 * AbstractJdbcOutboxStore store = JdbcOutboxStores.detect(dataSource);
 *
 * // Auto-detect from JDBC URL
 * AbstractJdbcOutboxStore store = JdbcOutboxStores.detect("jdbc:mysql://localhost/mydb");
 *
 * // Get by name
 * AbstractJdbcOutboxStore store = JdbcOutboxStores.get("postgresql");
 *
 * // List all registered outbox stores
 * List<AbstractJdbcOutboxStore> all = JdbcOutboxStores.all();
 * }</pre>
 */
public final class JdbcOutboxStores {

    private static final List<AbstractJdbcOutboxStore> STORES;
    private static final Map<String, AbstractJdbcOutboxStore> BY_NAME = new ConcurrentHashMap<>();

    static {
        STORES = ServiceLoader.load(AbstractJdbcOutboxStore.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        for (AbstractJdbcOutboxStore store : STORES) {
            BY_NAME.put(store.name().toLowerCase(), store);
        }
    }

    private JdbcOutboxStores() {
    }

    /**
     * Returns all registered outbox stores.
     */
    public static List<AbstractJdbcOutboxStore> all() {
        return STORES;
    }

    /**
     * Gets an outbox store by name.
     *
     * @param name outbox store name (case-insensitive)
     * @return the outbox store
     * @throws IllegalArgumentException if no outbox store found
     */
    public static AbstractJdbcOutboxStore get(String name) {
        Objects.requireNonNull(name, "name");
        AbstractJdbcOutboxStore store = BY_NAME.get(name.toLowerCase());
        if (store == null) {
            throw new IllegalArgumentException("Unknown outbox store: " + name +
                    ". Available: " + BY_NAME.keySet());
        }
        return store;
    }

    /**
     * Auto-detects outbox store from a DataSource.
     *
     * @param dataSource the data source
     * @return detected outbox store
     * @throws IllegalStateException if detection fails or no matching outbox store
     */
    public static AbstractJdbcOutboxStore detect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            return detect(url);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to detect outbox store from DataSource", e);
        }
    }

    /**
     * Auto-detects outbox store from a JDBC URL.
     *
     * @param jdbcUrl the JDBC URL
     * @return detected outbox store
     * @throws IllegalArgumentException if no matching outbox store found
     */
    public static AbstractJdbcOutboxStore detect(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            throw new IllegalArgumentException("JDBC URL cannot be null or empty");
        }

        for (AbstractJdbcOutboxStore store : STORES) {
            for (String prefix : store.jdbcUrlPrefixes()) {
                if (jdbcUrl.toLowerCase().startsWith(prefix.toLowerCase())) {
                    return store;
                }
            }
        }

        throw new IllegalArgumentException("No outbox store found for JDBC URL: " + jdbcUrl +
                ". Supported prefixes: " + allPrefixes());
    }

    /**
     * Auto-detects outbox store from a DataSource with a custom {@link JsonCodec}.
     *
     * @param dataSource the data source
     * @param jsonCodec  the JSON codec to use
     * @return detected outbox store configured with the given codec
     * @throws IllegalStateException if detection fails or no matching outbox store
     */
    public static AbstractJdbcOutboxStore detect(DataSource dataSource, JsonCodec jsonCodec) {
        Objects.requireNonNull(jsonCodec, "jsonCodec");
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            return detect(url, jsonCodec);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to detect outbox store from DataSource", e);
        }
    }

    /**
     * Auto-detects outbox store from a JDBC URL with a custom {@link JsonCodec}.
     *
     * @param jdbcUrl   the JDBC URL
     * @param jsonCodec the JSON codec to use
     * @return detected outbox store configured with the given codec
     * @throws IllegalArgumentException if no matching outbox store found
     */
    public static AbstractJdbcOutboxStore detect(String jdbcUrl, JsonCodec jsonCodec) {
        Objects.requireNonNull(jsonCodec, "jsonCodec");
        AbstractJdbcOutboxStore template = detect(jdbcUrl);
        return newInstance(template, jsonCodec);
    }

    private static AbstractJdbcOutboxStore newInstance(AbstractJdbcOutboxStore template, JsonCodec jsonCodec) {
        return template.withJsonCodec(jsonCodec);
    }

    private static List<String> allPrefixes() {
        return STORES.stream()
                .flatMap(s -> s.jdbcUrlPrefixes().stream())
                .toList();
    }
}
