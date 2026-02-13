package outbox.benchmark;

import outbox.benchmark.BenchmarkDataSourceFactory.DatabaseSetup;
import org.openjdk.jmh.annotations.*;
import outbox.OutboxWriter;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;

import java.util.concurrent.TimeUnit;

/**
 * Measures OutboxWriter.write() throughput (ops/sec) within a JDBC transaction.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar OutboxWriteBenchmark}
 * <p>MySQL: {@code java -jar benchmarks/target/benchmarks.jar -p database=mysql OutboxWriteBenchmark}
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class OutboxWriteBenchmark {

  private javax.sql.DataSource dataSource;
  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;
  private OutboxWriter writer;

  @Param({"h2"})
  private String database;

  @Param({"100", "1000", "10000"})
  private int payloadSize;

  private String payload;

  @Setup(Level.Trial)
  public void setup() {
    DatabaseSetup db = BenchmarkDataSourceFactory.create(database, "bench_write");
    BenchmarkDataSourceFactory.truncate(db.dataSource());

    dataSource = db.dataSource();
    connectionProvider = new DataSourceConnectionProvider(dataSource);
    txContext = new ThreadLocalTxContext();
    txManager = new JdbcTransactionManager(connectionProvider, txContext);
    writer = new OutboxWriter(txContext, db.store());
    payload = "{\"data\":\"" + "x".repeat(Math.max(0, payloadSize - 11)) + "\"}";
  }

  @Benchmark
  public String writeEvent() throws Exception {
    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      String eventId = writer.write("BenchEvent", payload);
      tx.commit();
      return eventId;
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    if (dataSource instanceof AutoCloseable ac) ac.close();
  }
}
