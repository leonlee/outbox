package outbox.benchmark;

import org.h2.jdbcx.JdbcDataSource;
import org.openjdk.jmh.annotations.*;
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;

import java.sql.Connection;
import java.util.concurrent.TimeUnit;

/**
 * Measures OutboxWriter.write() throughput (ops/sec) within a JDBC transaction.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar OutboxWriteBenchmark}
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class OutboxWriteBenchmark {

  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;
  private OutboxWriter writer;

  @Param({"100", "1000", "10000"})
  private int payloadSize;

  private String payload;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:bench_write;MODE=MySQL;DB_CLOSE_DELAY=-1");

    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute(BenchmarkSchema.CREATE_TABLE);
      conn.createStatement().execute(BenchmarkSchema.CREATE_INDEX);
    }

    connectionProvider = new DataSourceConnectionProvider(dataSource);
    txContext = new ThreadLocalTxContext();
    txManager = new JdbcTransactionManager(connectionProvider, txContext);
    writer = new OutboxWriter(txContext, new H2OutboxStore());
    payload = "x".repeat(payloadSize);
  }

  @Benchmark
  public String writeEvent() throws Exception {
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      String eventId = writer.write("BenchEvent", payload);
      tx.commit();
      return eventId;
    }
  }
}
