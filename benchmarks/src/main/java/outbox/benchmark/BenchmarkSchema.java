package outbox.benchmark;

/**
 * Shared H2 schema DDL for benchmarks.
 */
final class BenchmarkSchema {

  static final String CREATE_TABLE =
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

  static final String CREATE_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_status_available ON outbox_event(status, available_at, created_at)";

  private BenchmarkSchema() {}
}
