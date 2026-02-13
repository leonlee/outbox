package outbox.benchmark;

import outbox.benchmark.BenchmarkDataSourceFactory.DatabaseSetup;
import org.openjdk.jmh.annotations.*;
import outbox.OutboxWriter;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

/**
 * Measures poller throughput: pollPending + markDone per batch.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar OutboxPollerBenchmark}
 * <p>MySQL: {@code java -jar benchmarks/target/benchmarks.jar -p database=mysql OutboxPollerBenchmark}
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class OutboxPollerBenchmark {

  private DataSource dataSource;
  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;
  private OutboxWriter writer;
  private AbstractJdbcOutboxStore outboxStore;

  @Param({"h2"})
  private String database;

  @Param({"10", "50", "200"})
  private int batchSize;

  @Setup(Level.Trial)
  public void setup() {
    DatabaseSetup db = BenchmarkDataSourceFactory.create(database, "bench_poller");
    BenchmarkDataSourceFactory.truncate(db.dataSource());

    dataSource = db.dataSource();
    connectionProvider = new DataSourceConnectionProvider(dataSource);
    txContext = new ThreadLocalTxContext();
    txManager = new JdbcTransactionManager(connectionProvider, txContext);
    outboxStore = db.store();
    writer = new OutboxWriter(txContext, outboxStore);
  }

  @Setup(Level.Invocation)
  public void seedEvents() throws Exception {
    for (int i = 0; i < batchSize; i++) {
      try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
        writer.write("BenchEvent", "{\"i\":" + i + "}");
        tx.commit();
      }
    }
  }

  @Benchmark
  public int pollAndMarkDone() throws Exception {
    try (Connection conn = connectionProvider.getConnection()) {
      List<OutboxEvent> events = outboxStore.pollPending(
          conn, Instant.now().plusSeconds(1), Duration.ZERO, batchSize);
      if (!events.isEmpty()) {
        List<String> ids = events.stream()
            .map(OutboxEvent::eventId)
            .toList();
        outboxStore.markDoneBatch(conn, ids);
      }
      return events.size();
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    if (dataSource instanceof AutoCloseable ac) ac.close();
  }
}
