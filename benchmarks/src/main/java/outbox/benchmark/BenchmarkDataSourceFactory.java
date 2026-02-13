package outbox.benchmark;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.h2.jdbcx.JdbcDataSource;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.store.MySqlOutboxStore;
import outbox.jdbc.store.PostgresOutboxStore;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Creates a {@link DatabaseSetup} for the requested database type.
 *
 * <p>Supported types: {@code "h2"} (in-memory), {@code "mysql"}, and {@code "postgresql"} (external servers).
 * External database connection details are read from system properties:
 * <ul>
 *   <li>{@code bench.mysql.url} — default {@code jdbc:mysql://localhost:3306/outbox_bench}</li>
 *   <li>{@code bench.mysql.user} — default {@code root}</li>
 *   <li>{@code bench.mysql.password} — default {@code ""} (empty)</li>
 *   <li>{@code bench.pg.url} — default {@code jdbc:postgresql://localhost:5432/outbox_bench?stringtype=unspecified}</li>
 *   <li>{@code bench.pg.user} — default {@code postgres}</li>
 *   <li>{@code bench.pg.password} — default {@code postgres}</li>
 * </ul>
 */
final class BenchmarkDataSourceFactory {

  record DatabaseSetup(DataSource dataSource, AbstractJdbcOutboxStore store) {}

  private static final String H2_CREATE_TABLE =
      "CREATE TABLE IF NOT EXISTS outbox_event (" +
          "event_id VARCHAR(36) PRIMARY KEY," +
          "event_type VARCHAR(128) NOT NULL," +
          "aggregate_type VARCHAR(64)," +
          "aggregate_id VARCHAR(128)," +
          "tenant_id VARCHAR(64)," +
          "payload CLOB NOT NULL," +
          "headers CLOB," +
          "status TINYINT NOT NULL," +
          "attempts INT NOT NULL DEFAULT 0," +
          "available_at TIMESTAMP NOT NULL," +
          "created_at TIMESTAMP NOT NULL," +
          "done_at TIMESTAMP," +
          "last_error CLOB," +
          "locked_by VARCHAR(128)," +
          "locked_at TIMESTAMP" +
          ")";

  private static final String H2_CREATE_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_status_available ON outbox_event(status, available_at, created_at)";

  private static final String MYSQL_CREATE_TABLE =
      "CREATE TABLE IF NOT EXISTS outbox_event (" +
          "event_id VARCHAR(36) PRIMARY KEY," +
          "event_type VARCHAR(128) NOT NULL," +
          "aggregate_type VARCHAR(64)," +
          "aggregate_id VARCHAR(128)," +
          "tenant_id VARCHAR(64)," +
          "payload JSON NOT NULL," +
          "headers JSON," +
          "status TINYINT NOT NULL," +
          "attempts INT NOT NULL DEFAULT 0," +
          "available_at DATETIME(6) NOT NULL," +
          "created_at DATETIME(6) NOT NULL," +
          "done_at DATETIME(6)," +
          "last_error TEXT," +
          "locked_by VARCHAR(128)," +
          "locked_at DATETIME(6)" +
          ")";

  private static final String MYSQL_CREATE_INDEX =
      "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)";

  private static final String PG_CREATE_TABLE =
      "CREATE TABLE IF NOT EXISTS outbox_event (" +
          "event_id VARCHAR(36) PRIMARY KEY," +
          "event_type VARCHAR(128) NOT NULL," +
          "aggregate_type VARCHAR(64)," +
          "aggregate_id VARCHAR(128)," +
          "tenant_id VARCHAR(64)," +
          "payload JSONB NOT NULL," +
          "headers JSONB," +
          "status SMALLINT NOT NULL," +
          "attempts INT NOT NULL DEFAULT 0," +
          "available_at TIMESTAMPTZ NOT NULL," +
          "created_at TIMESTAMPTZ NOT NULL," +
          "done_at TIMESTAMPTZ," +
          "last_error TEXT," +
          "locked_by VARCHAR(128)," +
          "locked_at TIMESTAMPTZ" +
          ")";

  private static final String PG_CREATE_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_status_available ON outbox_event(status, available_at, created_at)";

  static DatabaseSetup create(String database, String dbName) {
    return switch (database) {
      case "h2" -> createH2(dbName);
      case "mysql" -> createMySql();
      case "postgresql" -> createPostgresql();
      default -> throw new IllegalArgumentException("Unsupported database: " + database);
    };
  }

  private static DatabaseSetup createH2(String dbName) {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    initSchema(ds, H2_CREATE_TABLE, H2_CREATE_INDEX);
    return new DatabaseSetup(ds, new H2OutboxStore());
  }

  private static DatabaseSetup createMySql() {
    String url = System.getProperty("bench.mysql.url", "jdbc:mysql://localhost:3306/outbox_bench");
    String user = System.getProperty("bench.mysql.user", "root");
    String password = System.getProperty("bench.mysql.password", "");

    DataSource rawDs = new DriverManagerDataSource(url, user, password);
    initSchema(rawDs, MYSQL_CREATE_TABLE, MYSQL_CREATE_INDEX);
    DataSource ds = wrapWithPool(rawDs, "bench-mysql");
    return new DatabaseSetup(ds, new MySqlOutboxStore());
  }

  private static DatabaseSetup createPostgresql() {
    String url = System.getProperty("bench.pg.url", "jdbc:postgresql://localhost:5432/outbox_bench?stringtype=unspecified");
    String user = System.getProperty("bench.pg.user", "postgres");
    String password = System.getProperty("bench.pg.password", "postgres");

    DataSource rawDs = new DriverManagerDataSource(url, user, password);
    initSchema(rawDs, PG_CREATE_TABLE, PG_CREATE_INDEX);
    DataSource ds = wrapWithPool(rawDs, "bench-pg");
    return new DatabaseSetup(ds, new PostgresOutboxStore());
  }

  private static DataSource wrapWithPool(DataSource dataSource, String poolName) {
    HikariConfig config = new HikariConfig();
    config.setDataSource(dataSource);
    config.setPoolName(poolName);
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    return new HikariDataSource(config);
  }

  private static class DriverManagerDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    DriverManagerDataSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pw) throws java.sql.SQLException {
      return DriverManager.getConnection(url, username, pw);
    }

    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getLogger("outbox.benchmark"); }
    @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
  }

  private static void initSchema(DataSource ds, String createTable, String createIndex) {
    try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute(createTable);
      try {
        stmt.execute(createIndex);
      } catch (Exception ignored) {
        // Index may already exist (MySQL CREATE INDEX lacks IF NOT EXISTS before 8.0.29)
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize benchmark schema", e);
    }
  }

  static void truncate(DataSource ds) {
    try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE outbox_event");
    } catch (Exception e) {
      throw new RuntimeException("Failed to truncate benchmark table", e);
    }
  }

  private BenchmarkDataSourceFactory() {}
}
