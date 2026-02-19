package outbox;

import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.DispatcherWriterHook;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.OutboxDispatcher;
import outbox.dispatch.RetryPolicy;
import outbox.poller.OutboxPoller;
import outbox.registry.ListenerRegistry;
import outbox.spi.ConnectionProvider;
import outbox.spi.MetricsExporter;
import outbox.spi.OutboxStore;
import outbox.spi.TxContext;
import outbox.util.JsonCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite entry point that wires an {@link OutboxDispatcher}, {@link OutboxPoller},
 * and {@link OutboxWriter} into a single {@link AutoCloseable} unit.
 *
 * <p>Three scenario-specific builders expose only the parameters relevant to each
 * deployment topology:
 * <ul>
 *   <li>{@link #singleNode()} — hot path + poller fallback (default)</li>
 *   <li>{@link #multiNode()} — hot path + poller with claim-based locking</li>
 *   <li>{@link #ordered()} — poller-only, single worker, no retry</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * try (Outbox outbox = Outbox.singleNode()
 *     .connectionProvider(connProvider)
 *     .txContext(txContext)
 *     .outboxStore(store)
 *     .listenerRegistry(registry)
 *     .build()) {
 *   OutboxWriter writer = outbox.writer();
 *   // use writer inside transactions...
 * }
 * }</pre>
 *
 * @see OutboxWriter
 * @see OutboxDispatcher
 * @see OutboxPoller
 */
public final class Outbox implements AutoCloseable {

  private final OutboxWriter writer;
  private final OutboxPoller poller;
  private final OutboxDispatcher dispatcher;

  private Outbox(OutboxWriter writer, OutboxPoller poller, OutboxDispatcher dispatcher) {
    this.writer = writer;
    this.poller = poller;
    this.dispatcher = dispatcher;
  }

  /**
   * Returns the writer for persisting events within transactions.
   *
   * @return the outbox writer
   */
  public OutboxWriter writer() {
    return writer;
  }

  /**
   * Shuts down the poller first (stop feeding cold queue), then the dispatcher
   * (drain remaining events).
   */
  @Override
  public void close() {
    poller.close();
    dispatcher.close();
  }

  /**
   * Creates a builder for single-node deployments with hot path + poller fallback.
   *
   * @return a new single-node builder
   */
  public static SingleNodeBuilder singleNode() {
    return new SingleNodeBuilder();
  }

  /**
   * Creates a builder for multi-node deployments with hot path + claim-based poller locking.
   *
   * @return a new multi-node builder
   */
  public static MultiNodeBuilder multiNode() {
    return new MultiNodeBuilder();
  }

  /**
   * Creates a builder for ordered delivery (poller-only, single worker, no retry).
   *
   * @return a new ordered-delivery builder
   */
  public static OrderedBuilder ordered() {
    return new OrderedBuilder();
  }

  // ── Abstract builder ─────────────────────────────────────────────

  /**
   * Base builder with shared required and optional parameters.
   *
   * @param <B> the concrete builder type (CRTP)
   */
  public static abstract sealed class AbstractBuilder<B extends AbstractBuilder<B>>
      permits SingleNodeBuilder, MultiNodeBuilder, OrderedBuilder {

    ConnectionProvider connectionProvider;
    TxContext txContext;
    OutboxStore outboxStore;
    ListenerRegistry listenerRegistry;
    MetricsExporter metrics;
    JsonCodec jsonCodec;
    final List<EventInterceptor> interceptors = new ArrayList<>();
    long intervalMs = 5000;
    int batchSize = 50;
    Duration skipRecent;
    long drainTimeoutMs = 5000;

    AbstractBuilder() {}

    @SuppressWarnings("unchecked")
    private B self() {
      return (B) this;
    }

    /**
     * Sets the connection provider for obtaining JDBC connections.
     *
     * <p><b>Required.</b>
     *
     * @param connectionProvider the connection provider
     * @return this builder
     */
    public B connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = connectionProvider;
      return self();
    }

    /**
     * Sets the transaction context for managing transaction lifecycle.
     *
     * <p><b>Required.</b>
     *
     * @param txContext the transaction context
     * @return this builder
     */
    public B txContext(TxContext txContext) {
      this.txContext = txContext;
      return self();
    }

    /**
     * Sets the outbox store for event persistence.
     *
     * <p><b>Required.</b>
     *
     * @param outboxStore the persistence backend
     * @return this builder
     */
    public B outboxStore(OutboxStore outboxStore) {
      this.outboxStore = outboxStore;
      return self();
    }

    /**
     * Sets the listener registry that maps events to listeners.
     *
     * <p><b>Required.</b>
     *
     * @param listenerRegistry the listener registry
     * @return this builder
     */
    public B listenerRegistry(ListenerRegistry listenerRegistry) {
      this.listenerRegistry = listenerRegistry;
      return self();
    }

    /**
     * Sets the metrics exporter.
     *
     * <p>Optional. Defaults to {@link MetricsExporter#NOOP}.
     *
     * @param metrics the metrics exporter
     * @return this builder
     */
    public B metrics(MetricsExporter metrics) {
      this.metrics = metrics;
      return self();
    }

    /**
     * Sets a custom JSON codec for decoding event headers.
     *
     * <p>Optional. Defaults to {@link JsonCodec#getDefault()}.
     *
     * @param jsonCodec the JSON codec
     * @return this builder
     */
    public B jsonCodec(JsonCodec jsonCodec) {
      this.jsonCodec = jsonCodec;
      return self();
    }

    /**
     * Appends a single event interceptor for before/after dispatch hooks.
     *
     * @param interceptor the interceptor to add
     * @return this builder
     */
    public B interceptor(EventInterceptor interceptor) {
      this.interceptors.add(interceptor);
      return self();
    }

    /**
     * Appends multiple event interceptors for before/after dispatch hooks.
     *
     * @param interceptors the interceptors to add
     * @return this builder
     */
    public B interceptors(List<EventInterceptor> interceptors) {
      this.interceptors.addAll(interceptors);
      return self();
    }

    /**
     * Sets the polling interval in milliseconds.
     *
     * <p>Optional. Defaults to {@code 5000} ms.
     *
     * @param intervalMs polling interval in milliseconds
     * @return this builder
     */
    public B intervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
      return self();
    }

    /**
     * Sets the maximum number of events fetched per poll cycle.
     *
     * <p>Optional. Defaults to {@code 50}.
     *
     * @param batchSize max events per poll
     * @return this builder
     */
    public B batchSize(int batchSize) {
      this.batchSize = batchSize;
      return self();
    }

    /**
     * Sets a grace period to skip recently created events during polling.
     *
     * <p>Optional. Defaults to {@link Duration#ZERO}.
     *
     * @param skipRecent duration to skip recent events
     * @return this builder
     */
    public B skipRecent(Duration skipRecent) {
      this.skipRecent = skipRecent;
      return self();
    }

    /**
     * Sets the maximum time in milliseconds to wait for in-flight events during shutdown.
     *
     * <p>Optional. Defaults to {@code 5000} ms.
     *
     * @param drainTimeoutMs drain timeout in milliseconds
     * @return this builder
     */
    public B drainTimeoutMs(long drainTimeoutMs) {
      this.drainTimeoutMs = drainTimeoutMs;
      return self();
    }

    void validateRequired() {
      Objects.requireNonNull(connectionProvider, "connectionProvider");
      Objects.requireNonNull(txContext, "txContext");
      Objects.requireNonNull(outboxStore, "outboxStore");
      Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    }

    /**
     * Builds the dispatcher, poller (with optional claim locking), and writer
     * into a composite Outbox. If poller construction fails, the dispatcher is
     * closed before rethrowing.
     */
    Outbox buildComposite(
        int workerCount, int hotQueueCapacity, int coldQueueCapacity,
        int maxAttempts, RetryPolicy retryPolicy,
        String ownerId, Duration lockTimeout,
        boolean hotPathEnabled) {

      OutboxDispatcher.Builder db = OutboxDispatcher.builder()
          .connectionProvider(connectionProvider)
          .outboxStore(outboxStore)
          .listenerRegistry(listenerRegistry)
          .workerCount(workerCount)
          .hotQueueCapacity(hotQueueCapacity)
          .coldQueueCapacity(coldQueueCapacity)
          .maxAttempts(maxAttempts)
          .drainTimeoutMs(drainTimeoutMs)
          .interceptors(interceptors);
      if (retryPolicy != null) {
        db.retryPolicy(retryPolicy);
      }
      if (metrics != null) {
        db.metrics(metrics);
      }

      OutboxDispatcher dispatcher = db.build();
      OutboxPoller poller;
      try {
        OutboxPoller.Builder pb = OutboxPoller.builder()
            .connectionProvider(connectionProvider)
            .outboxStore(outboxStore)
            .handler(new DispatcherPollerHandler(dispatcher))
            .batchSize(batchSize)
            .intervalMs(intervalMs);
        if (skipRecent != null) {
          pb.skipRecent(skipRecent);
        }
        if (metrics != null) {
          pb.metrics(metrics);
        }
        if (jsonCodec != null) {
          pb.jsonCodec(jsonCodec);
        }
        if (ownerId != null) {
          pb.claimLocking(ownerId, lockTimeout);
        }
        poller = pb.build();
      } catch (RuntimeException e) {
        dispatcher.close();
        throw e;
      }
      poller.start();

      OutboxWriter writer;
      if (hotPathEnabled) {
        WriterHook writerHook = new DispatcherWriterHook(dispatcher, metrics);
        writer = new OutboxWriter(txContext, outboxStore, writerHook);
      } else {
        writer = new OutboxWriter(txContext, outboxStore);
      }
      return new Outbox(writer, poller, dispatcher);
    }

    /**
     * Builds and starts the outbox composite.
     *
     * @return a new {@link Outbox} instance
     */
    public abstract Outbox build();
  }

  // ── Single-node builder ──────────────────────────────────────────

  /**
   * Builder for single-node deployments: hot path + poller fallback.
   */
  public static final class SingleNodeBuilder extends AbstractBuilder<SingleNodeBuilder> {
    private int workerCount = 4;
    private int hotQueueCapacity = 1000;
    private int coldQueueCapacity = 1000;
    private int maxAttempts = 10;
    private RetryPolicy retryPolicy;

    SingleNodeBuilder() {}

    /**
     * Sets the number of dispatcher worker threads.
     *
     * <p>Optional. Defaults to {@code 4}.
     *
     * @param workerCount number of worker threads
     * @return this builder
     */
    public SingleNodeBuilder workerCount(int workerCount) {
      this.workerCount = workerCount;
      return this;
    }

    /**
     * Sets the bounded capacity of the hot queue.
     *
     * <p>Optional. Defaults to {@code 1000}.
     *
     * @param hotQueueCapacity maximum hot queue size
     * @return this builder
     */
    public SingleNodeBuilder hotQueueCapacity(int hotQueueCapacity) {
      this.hotQueueCapacity = hotQueueCapacity;
      return this;
    }

    /**
     * Sets the bounded capacity of the cold queue.
     *
     * <p>Optional. Defaults to {@code 1000}.
     *
     * @param coldQueueCapacity maximum cold queue size
     * @return this builder
     */
    public SingleNodeBuilder coldQueueCapacity(int coldQueueCapacity) {
      this.coldQueueCapacity = coldQueueCapacity;
      return this;
    }

    /**
     * Sets the maximum number of delivery attempts before marking DEAD.
     *
     * <p>Optional. Defaults to {@code 10}.
     *
     * @param maxAttempts maximum attempts per event
     * @return this builder
     */
    public SingleNodeBuilder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * Sets the retry policy for computing delay between attempts.
     *
     * <p>Optional. Defaults to exponential backoff.
     *
     * @param retryPolicy the retry policy
     * @return this builder
     */
    public SingleNodeBuilder retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    @Override
    public Outbox build() {
      validateRequired();
      return buildComposite(
          workerCount, hotQueueCapacity, coldQueueCapacity,
          maxAttempts, retryPolicy,
          null, null, true);
    }
  }

  // ── Multi-node builder ───────────────────────────────────────────

  /**
   * Builder for multi-node deployments: hot path + claim-based poller locking.
   */
  public static final class MultiNodeBuilder extends AbstractBuilder<MultiNodeBuilder> {
    private int workerCount = 4;
    private int hotQueueCapacity = 1000;
    private int coldQueueCapacity = 1000;
    private int maxAttempts = 10;
    private RetryPolicy retryPolicy;
    private String ownerId;
    private Duration lockTimeout;

    MultiNodeBuilder() {}

    /**
     * Sets the number of dispatcher worker threads.
     *
     * <p>Optional. Defaults to {@code 4}.
     *
     * @param workerCount number of worker threads
     * @return this builder
     */
    public MultiNodeBuilder workerCount(int workerCount) {
      this.workerCount = workerCount;
      return this;
    }

    /**
     * Sets the bounded capacity of the hot queue.
     *
     * <p>Optional. Defaults to {@code 1000}.
     *
     * @param hotQueueCapacity maximum hot queue size
     * @return this builder
     */
    public MultiNodeBuilder hotQueueCapacity(int hotQueueCapacity) {
      this.hotQueueCapacity = hotQueueCapacity;
      return this;
    }

    /**
     * Sets the bounded capacity of the cold queue.
     *
     * <p>Optional. Defaults to {@code 1000}.
     *
     * @param coldQueueCapacity maximum cold queue size
     * @return this builder
     */
    public MultiNodeBuilder coldQueueCapacity(int coldQueueCapacity) {
      this.coldQueueCapacity = coldQueueCapacity;
      return this;
    }

    /**
     * Sets the maximum number of delivery attempts before marking DEAD.
     *
     * <p>Optional. Defaults to {@code 10}.
     *
     * @param maxAttempts maximum attempts per event
     * @return this builder
     */
    public MultiNodeBuilder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * Sets the retry policy for computing delay between attempts.
     *
     * <p>Optional. Defaults to exponential backoff.
     *
     * @param retryPolicy the retry policy
     * @return this builder
     */
    public MultiNodeBuilder retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    /**
     * Enables claim-based locking with an auto-generated owner ID.
     *
     * <p><b>Required.</b>
     *
     * @param lockTimeout how long a claimed event stays locked
     * @return this builder
     */
    public MultiNodeBuilder claimLocking(Duration lockTimeout) {
      return claimLocking("poller-" + UUID.randomUUID().toString().substring(0, 8), lockTimeout);
    }

    /**
     * Enables claim-based locking with an explicit owner ID.
     *
     * <p><b>Required.</b>
     *
     * @param ownerId   unique identifier for this instance
     * @param lockTimeout how long a claimed event stays locked
     * @return this builder
     */
    public MultiNodeBuilder claimLocking(String ownerId, Duration lockTimeout) {
      this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
      this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
      return this;
    }

    @Override
    public Outbox build() {
      validateRequired();
      if (lockTimeout == null) {
        throw new IllegalStateException("claimLocking() is required for multiNode()");
      }
      return buildComposite(
          workerCount, hotQueueCapacity, coldQueueCapacity,
          maxAttempts, retryPolicy,
          ownerId, lockTimeout, true);
    }
  }

  // ── Ordered builder ──────────────────────────────────────────────

  /**
   * Builder for ordered delivery: poller-only, single worker, no retry.
   *
   * <p>Forces {@code workerCount=1}, {@code maxAttempts=1}, and no {@link WriterHook}
   * (events are delivered exclusively via the poller).
   */
  public static final class OrderedBuilder extends AbstractBuilder<OrderedBuilder> {

    OrderedBuilder() {}

    @Override
    public Outbox build() {
      validateRequired();
      return buildComposite(1, 1000, 1000, 1, null, null, null, false);
    }
  }
}
