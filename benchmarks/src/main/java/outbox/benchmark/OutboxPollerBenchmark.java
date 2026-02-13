package outbox.benchmark;

import org.h2.jdbcx.JdbcDataSource;
import org.openjdk.jmh.annotations.*;
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures poller throughput: pollPending + markDone per batch.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar OutboxPollerBenchmark}
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class OutboxPollerBenchmark {

  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;
  private OutboxWriter writer;
  private H2OutboxStore outboxStore;

  @Param({"10", "50", "200"})
  private int batchSize;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:bench_poller;MODE=MySQL;DB_CLOSE_DELAY=-1");

    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute(BenchmarkSchema.CREATE_TABLE);
      conn.createStatement().execute(BenchmarkSchema.CREATE_INDEX);
    }

    connectionProvider = new DataSourceConnectionProvider(dataSource);
    txContext = new ThreadLocalTxContext();
    txManager = new JdbcTransactionManager(connectionProvider, txContext);
    outboxStore = new H2OutboxStore();
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
      for (OutboxEvent event : events) {
        outboxStore.markDone(conn, event.eventId());
      }
      return events.size();
    }
  }
}
