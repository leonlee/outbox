package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.EventListener;
import outbox.registry.ListenerRegistry;
import outbox.spi.ConnectionProvider;
import outbox.spi.OutboxStore;
import outbox.spi.MetricsExporter;
import outbox.util.DaemonThreadFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dual-queue event processor that dispatches outbox events to registered listeners.
 *
 * <p>Events arrive via two paths: the <em>hot queue</em> (after-commit callbacks) and
 * the <em>cold queue</em> (poller fallback). Worker threads drain both queues using a
 * weighted 2:1 round-robin favoring the hot queue. Duplicate processing is prevented
 * by an {@link InFlightTracker}.
 *
 * <p>Create instances via {@link #builder()}. This class is thread-safe and implements
 * {@link AutoCloseable} for graceful shutdown with a configurable drain timeout.
 *
 * @see OutboxDispatcher.Builder
 * @see DispatcherWriterHook
 * @see DispatcherPollerHandler
 */
public final class OutboxDispatcher implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(OutboxDispatcher.class.getName());

  private static final long QUEUE_POLL_TIMEOUT_MS = 50;

  private final BlockingQueue<QueuedEvent> hotQueue;
  private final BlockingQueue<QueuedEvent> coldQueue;
  private final ExecutorService workers;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicInteger pollCounter = new AtomicInteger(0);

  private final ConnectionProvider connectionProvider;
  private final OutboxStore outboxStore;
  private final ListenerRegistry listenerRegistry;
  private final InFlightTracker inFlightTracker;
  private final RetryPolicy retryPolicy;
  private final int maxAttempts;
  private final MetricsExporter metrics;
  private final List<EventInterceptor> interceptors;
  private final long drainTimeoutMs;

  private OutboxDispatcher(Builder builder) {
    this.connectionProvider = Objects.requireNonNull(builder.connectionProvider, "connectionProvider");
    this.outboxStore = Objects.requireNonNull(builder.outboxStore, "outboxStore");
    this.listenerRegistry = Objects.requireNonNull(builder.listenerRegistry, "listenerRegistry");
    this.inFlightTracker = builder.inFlightTracker != null
        ? builder.inFlightTracker : new DefaultInFlightTracker();
    this.retryPolicy = builder.retryPolicy != null
        ? builder.retryPolicy : new ExponentialBackoffRetryPolicy(200, 60_000);
    this.metrics = builder.metrics != null ? builder.metrics : MetricsExporter.NOOP;
    this.interceptors = Collections.unmodifiableList(new ArrayList<>(builder.interceptors));
    this.drainTimeoutMs = builder.drainTimeoutMs;

    int maxAttempts = builder.maxAttempts;
    int workerCount = builder.workerCount;
    int hotQueueCapacity = builder.hotQueueCapacity;
    int coldQueueCapacity = builder.coldQueueCapacity;

    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (workerCount < 0) {
      throw new IllegalArgumentException("workerCount must be >= 0");
    }
    if (hotQueueCapacity <= 0 || coldQueueCapacity <= 0) {
      throw new IllegalArgumentException("Queue capacities must be > 0");
    }
    this.maxAttempts = maxAttempts;

    this.hotQueue = new ArrayBlockingQueue<>(hotQueueCapacity);
    this.coldQueue = new ArrayBlockingQueue<>(coldQueueCapacity);

    if (workerCount > 0) {
      this.workers = Executors.newFixedThreadPool(workerCount, new DaemonThreadFactory("outbox-dispatcher-"));
      for (int i = 0; i < workerCount; i++) {
        workers.submit(this::workerLoop);
      }
    } else {
      // workerCount=0: no workers started; events remain queued (testing only)
      logger.warning("workerCount=0: no dispatch workers started; events will not be processed");
      this.workers = Executors.newCachedThreadPool(new DaemonThreadFactory("outbox-dispatcher-"));
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Offers an event to the hot queue. Returns {@code false} if the queue is full
   * or the dispatcher is no longer accepting events.
   *
   * @param event the queued event to enqueue
   * @return {@code true} if the event was accepted
   */
  public boolean enqueueHot(QueuedEvent event) {
    if (!accepting.get()) return false;
    boolean enqueued = hotQueue.offer(event);
    metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
    return enqueued;
  }

  /**
   * Offers an event to the cold queue. Returns {@code false} if the queue is full
   * or the dispatcher is no longer accepting events.
   *
   * @param event the queued event to enqueue
   * @return {@code true} if the event was accepted
   */
  public boolean enqueueCold(QueuedEvent event) {
    if (!accepting.get()) return false;
    boolean enqueued = coldQueue.offer(event);
    metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
    return enqueued;
  }

  public int coldQueueRemainingCapacity() {
    return coldQueue.remainingCapacity();
  }

  private QueuedEvent pollFairly() throws InterruptedException {
    int cycle = pollCounter.getAndIncrement();
    BlockingQueue<QueuedEvent> primary;
    BlockingQueue<QueuedEvent> secondary;
    // Mask sign bit to stay non-negative after int overflow
    if ((cycle & 0x7FFFFFFF) % 3 == 2) {
      primary = coldQueue;
      secondary = hotQueue;
    } else {
      primary = hotQueue;
      secondary = coldQueue;
    }
    QueuedEvent event = primary.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (event == null) {
      event = secondary.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
    return event;
  }

  private void workerLoop() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        if (!running.get() && hotQueue.isEmpty() && coldQueue.isEmpty()) {
          break;
        }
        QueuedEvent event = pollFairly();
        if (event == null) {
          if (!running.get()) break;
          continue;
        }
        dispatchEvent(event);
        metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Dispatcher loop error", t);
      }
    }
  }

  private void dispatchEvent(QueuedEvent event) {
    String eventId = event.envelope().eventId();
    if (!inFlightTracker.tryAcquire(eventId)) {
      return;
    }
    try {
      deliverEvent(event.envelope());
      markDone(eventId);
      metrics.incrementDispatchSuccess();
    } catch (Exception e) {
      handleFailure(event, e);
    } finally {
      inFlightTracker.release(eventId);
    }
  }

  private void deliverEvent(EventEnvelope envelope) throws Exception {
    int completedBefore = 0;
    try {
      for (int i = 0; i < interceptors.size(); i++) {
        interceptors.get(i).beforeDispatch(envelope);
        completedBefore = i + 1;
      }

      EventListener listener = listenerRegistry.listenerFor(
          envelope.aggregateType(), envelope.eventType());
      if (listener == null) {
        throw new UnroutableEventException("No listener for aggregateType="
            + envelope.aggregateType() + ", eventType=" + envelope.eventType());
      }
      listener.onEvent(envelope);

      runAfterDispatch(envelope, null, completedBefore);
    } catch (Exception e) {
      runAfterDispatch(envelope, e, completedBefore);
      throw e;
    }
  }

  private void runAfterDispatch(EventEnvelope envelope, Exception error, int count) {
    for (int i = count - 1; i >= 0; i--) {
      try {
        interceptors.get(i).afterDispatch(envelope, error);
      } catch (Exception ex) {
        logger.log(Level.WARNING, "Interceptor afterDispatch failed", ex);
      }
    }
  }

  private void handleFailure(QueuedEvent event, Exception failure) {
    String eventId = event.envelope().eventId();
    if (failure instanceof UnroutableEventException) {
      markDead(eventId, failure);
      metrics.incrementDispatchDead();
      logger.log(Level.SEVERE, "Unroutable event marked DEAD: " + eventId, failure);
      return;
    }

    int nextAttempt = event.attempts() + 1;
    if (nextAttempt >= maxAttempts) {
      markDead(eventId, failure);
      metrics.incrementDispatchDead();
      logger.log(Level.SEVERE, "Event moved to DEAD after max attempts: " + eventId, failure);
    } else {
      long delayMs = retryPolicy.computeDelayMs(nextAttempt);
      Instant nextAt = Instant.now().plusMillis(delayMs);
      markRetry(eventId, nextAt, failure);
      metrics.incrementDispatchFailure();
    }
  }

  private void markDone(String eventId) {
    withConnection("mark DONE", eventId,
        conn -> outboxStore.markDone(conn, eventId));
  }

  private void markRetry(String eventId, Instant nextAt, Exception failure) {
    withConnection("mark RETRY", eventId,
        conn -> outboxStore.markRetry(conn, eventId, nextAt, failure == null ? null : failure.getMessage()));
  }

  private void markDead(String eventId, Exception failure) {
    withConnection("mark DEAD", eventId,
        conn -> outboxStore.markDead(conn, eventId, failure == null ? null : failure.getMessage()));
  }

  private void withConnection(String action, String eventId, SqlAction op) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      op.execute(conn);
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to " + action + " for eventId=" + eventId, e);
    }
  }

  @FunctionalInterface
  private interface SqlAction {
    void execute(Connection conn) throws SQLException;
  }

  /**
   * Initiates graceful shutdown: stops accepting new events, drains remaining queued
   * events within the configured drain timeout, then shuts down worker threads.
   */
  @Override
  public void close() {
    accepting.set(false);
    running.set(false);
    workers.shutdown();
    try {
      if (!workers.awaitTermination(drainTimeoutMs, TimeUnit.MILLISECONDS)) {
        logger.log(Level.WARNING, "Drain timeout exceeded; forcing shutdown. "
            + "Hot remaining: " + hotQueue.size() + ", Cold remaining: " + coldQueue.size());
        workers.shutdownNow();
        workers.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** Builder for {@link OutboxDispatcher}. */
  public static final class Builder {
    private ConnectionProvider connectionProvider;
    private OutboxStore outboxStore;
    private ListenerRegistry listenerRegistry;
    private InFlightTracker inFlightTracker;
    private RetryPolicy retryPolicy;
    private int maxAttempts = 10;
    private int workerCount = 4;
    private int hotQueueCapacity = 1000;
    private int coldQueueCapacity = 1000;
    private MetricsExporter metrics;
    private final List<EventInterceptor> interceptors = new ArrayList<>();
    private long drainTimeoutMs = 5000;

    private Builder() {}

    /**
     * Sets the connection provider for obtaining JDBC connections when marking events.
     *
     * <p><b>Required.</b>
     *
     * @param connectionProvider the connection provider
     * @return this builder
     */
    public Builder connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = connectionProvider;
      return this;
    }

    /**
     * Sets the outbox store used to update event status (DONE, RETRY, DEAD).
     *
     * <p><b>Required.</b>
     *
     * @param outboxStore the persistence backend
     * @return this builder
     */
    public Builder outboxStore(OutboxStore outboxStore) {
      this.outboxStore = outboxStore;
      return this;
    }

    /**
     * Sets the listener registry that maps {@code (aggregateType, eventType)} pairs to listeners.
     *
     * <p><b>Required.</b>
     *
     * @param listenerRegistry the listener registry
     * @return this builder
     */
    public Builder listenerRegistry(ListenerRegistry listenerRegistry) {
      this.listenerRegistry = listenerRegistry;
      return this;
    }

    /**
     * Sets a custom in-flight tracker for deduplicating concurrent event processing.
     *
     * <p>Optional. Defaults to {@link DefaultInFlightTracker}.
     *
     * @param inFlightTracker the tracker implementation
     * @return this builder
     */
    public Builder inFlightTracker(InFlightTracker inFlightTracker) {
      this.inFlightTracker = inFlightTracker;
      return this;
    }

    /**
     * Sets the retry policy that computes delay between attempts on failure.
     *
     * <p>Optional. Defaults to {@link ExponentialBackoffRetryPolicy} with
     * {@code baseDelayMs=200} and {@code maxDelayMs=60000}.
     *
     * @param retryPolicy the retry policy
     * @return this builder
     */
    public Builder retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    /**
     * Sets the maximum number of delivery attempts before an event is marked DEAD.
     *
     * <p>Optional. Defaults to {@code 10}. Must be &ge; 1.
     *
     * @param maxAttempts maximum attempts per event
     * @return this builder
     */
    public Builder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * Sets the number of worker threads that drain and dispatch events.
     *
     * <p>Optional. Defaults to {@code 4}. Must be &ge; 0. Setting to {@code 0}
     * disables processing (useful for testing only).
     *
     * @param workerCount number of dispatch worker threads
     * @return this builder
     */
    public Builder workerCount(int workerCount) {
      this.workerCount = workerCount;
      return this;
    }

    /**
     * Sets the bounded capacity of the hot queue (after-commit events).
     *
     * <p>Optional. Defaults to {@code 1000}. Must be &gt; 0.
     *
     * @param hotQueueCapacity maximum number of events in the hot queue
     * @return this builder
     */
    public Builder hotQueueCapacity(int hotQueueCapacity) {
      this.hotQueueCapacity = hotQueueCapacity;
      return this;
    }

    /**
     * Sets the bounded capacity of the cold queue (poller-sourced events).
     *
     * <p>Optional. Defaults to {@code 1000}. Must be &gt; 0.
     *
     * @param coldQueueCapacity maximum number of events in the cold queue
     * @return this builder
     */
    public Builder coldQueueCapacity(int coldQueueCapacity) {
      this.coldQueueCapacity = coldQueueCapacity;
      return this;
    }

    /**
     * Sets the metrics exporter for recording dispatch counters and queue depths.
     *
     * <p>Optional. Defaults to {@link MetricsExporter#NOOP}.
     *
     * @param metrics the metrics exporter
     * @return this builder
     */
    public Builder metrics(MetricsExporter metrics) {
      this.metrics = metrics;
      return this;
    }

    /**
     * Appends a single event interceptor for before/after dispatch hooks.
     *
     * <p>Optional. Interceptors are invoked in registration order before dispatch,
     * and in reverse order after dispatch.
     *
     * @param interceptor the interceptor to add
     * @return this builder
     */
    public Builder interceptor(EventInterceptor interceptor) {
      this.interceptors.add(interceptor);
      return this;
    }

    /**
     * Appends multiple event interceptors for before/after dispatch hooks.
     *
     * <p>Optional. Interceptors are invoked in registration order before dispatch,
     * and in reverse order after dispatch.
     *
     * @param interceptors the interceptors to add
     * @return this builder
     */
    public Builder interceptors(List<EventInterceptor> interceptors) {
      this.interceptors.addAll(interceptors);
      return this;
    }

    /**
     * Sets the maximum time in milliseconds to wait for in-flight events during shutdown.
     *
     * <p>Optional. Defaults to {@code 5000} ms.
     *
     * @param drainTimeoutMs drain timeout in milliseconds
     * @return this builder
     */
    public Builder drainTimeoutMs(long drainTimeoutMs) {
      this.drainTimeoutMs = drainTimeoutMs;
      return this;
    }

    /**
     * Builds and starts the dispatcher. Worker threads begin draining queues immediately.
     *
     * @return a new {@link OutboxDispatcher} instance
     * @throws NullPointerException if {@code connectionProvider}, {@code outboxStore},
     *     or {@code listenerRegistry} is null
     * @throws IllegalArgumentException if {@code maxAttempts < 1}, {@code workerCount < 0},
     *     or any queue capacity is &le; 0
     */
    public OutboxDispatcher build() {
      return new OutboxDispatcher(this);
    }
  }

}
