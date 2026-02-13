package outbox.benchmark;

import org.h2.jdbcx.JdbcDataSource;
import org.openjdk.jmh.annotations.*;
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.dispatch.DispatcherCommitHook;
import outbox.dispatch.OutboxDispatcher;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;
import outbox.registry.DefaultListenerRegistry;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Measures hot-path end-to-end latency: write → afterCommit → dispatch → listener callback.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar OutboxDispatchBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class OutboxDispatchBenchmark {

  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;
  private OutboxWriter writer;
  private OutboxDispatcher dispatcher;

  @Param({"100", "1000", "10000"})
  private int payloadSize;

  private String payload;
  private final AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();

  @Setup(Level.Trial)
  public void setup() throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:bench_dispatch;MODE=MySQL;DB_CLOSE_DELAY=-1");

    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute(BenchmarkSchema.CREATE_TABLE);
      conn.createStatement().execute(BenchmarkSchema.CREATE_INDEX);
    }

    connectionProvider = new DataSourceConnectionProvider(dataSource);
    txContext = new ThreadLocalTxContext();
    txManager = new JdbcTransactionManager(connectionProvider, txContext);

    dispatcher = OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(new H2OutboxStore())
        .listenerRegistry(new DefaultListenerRegistry()
            .register("BenchEvent", event -> {
              CountDownLatch latch = latchRef.get();
              if (latch != null) latch.countDown();
            }))
        .workerCount(2)
        .hotQueueCapacity(1000)
        .coldQueueCapacity(100)
        .build();

    writer = new OutboxWriter(txContext, new H2OutboxStore(), new DispatcherCommitHook(dispatcher));
    payload = "x".repeat(payloadSize);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    dispatcher.close();
  }

  @Benchmark
  public void writeAndDispatch() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    latchRef.set(latch);

    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      writer.write("BenchEvent", payload);
      tx.commit();
    }

    latch.await(5, TimeUnit.SECONDS);
  }
}
