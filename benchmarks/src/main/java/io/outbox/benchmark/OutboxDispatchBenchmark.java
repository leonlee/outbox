package io.outbox.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import io.outbox.OutboxWriter;
import io.outbox.benchmark.BenchmarkDataSourceFactory.DatabaseSetup;
import io.outbox.dispatch.DispatcherWriterHook;
import io.outbox.dispatch.OutboxDispatcher;
import io.outbox.jdbc.DataSourceConnectionProvider;
import io.outbox.jdbc.tx.JdbcTransactionManager;
import io.outbox.jdbc.tx.ThreadLocalTxContext;
import io.outbox.registry.DefaultListenerRegistry;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Measures hot-path end-to-end latency: write -> afterCommit -> dispatch -> listener callback.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar OutboxDispatchBenchmark}
 * <p>MySQL: {@code java -jar benchmarks/target/benchmarks.jar -p database=mysql OutboxDispatchBenchmark}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
@Threads(1)
public class OutboxDispatchBenchmark {

    private DataSource dataSource;
    private DataSourceConnectionProvider connectionProvider;
    private ThreadLocalTxContext txContext;
    private JdbcTransactionManager txManager;
    private OutboxWriter writer;
    private OutboxDispatcher dispatcher;

    @Param({"h2"})
    private String database;

    @Param({"100", "1000", "10000"})
    private int payloadSize;

    private String payload;
    private final AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();

    @Setup(Level.Trial)
    public void setup() {
        DatabaseSetup db = BenchmarkDataSourceFactory.create(database, "bench_dispatch");
        BenchmarkDataSourceFactory.truncate(db.dataSource());

        dataSource = db.dataSource();
        connectionProvider = new DataSourceConnectionProvider(dataSource);
        txContext = new ThreadLocalTxContext();
        txManager = new JdbcTransactionManager(connectionProvider, txContext);

        dispatcher = OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(db.store())
                .listenerRegistry(new DefaultListenerRegistry()
                        .register("BenchEvent", event -> {
                            CountDownLatch latch = latchRef.get();
                            if (latch != null) latch.countDown();
                        }))
                .workerCount(2)
                .hotQueueCapacity(1000)
                .coldQueueCapacity(100)
                .build();

        writer = new OutboxWriter(txContext, db.store(), new DispatcherWriterHook(dispatcher));
        payload = "{\"data\":\"" + "x".repeat(Math.max(0, payloadSize - 11)) + "\"}";
    }

    @Setup(Level.Iteration)
    public void truncate() {
        BenchmarkDataSourceFactory.truncate(dataSource);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        dispatcher.close();
        if (dataSource instanceof AutoCloseable ac) ac.close();
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
