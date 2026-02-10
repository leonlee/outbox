package outbox.dispatch;

import outbox.EventEnvelope;
import outbox.EventListener;
import outbox.registry.ListenerRegistry;
import outbox.spi.ConnectionProvider;
import outbox.spi.EventStore;
import outbox.spi.MetricsExporter;

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

import outbox.util.DaemonThreadFactory;

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
  private final EventStore eventStore;
  private final ListenerRegistry listenerRegistry;
  private final InFlightTracker inFlightTracker;
  private final RetryPolicy retryPolicy;
  private final int maxAttempts;
  private final MetricsExporter metrics;
  private final List<EventInterceptor> interceptors;
  private final long drainTimeoutMs;

  private OutboxDispatcher(Builder builder) {
    this.connectionProvider = Objects.requireNonNull(builder.connectionProvider, "connectionProvider");
    this.eventStore = Objects.requireNonNull(builder.eventStore, "eventStore");
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
      // workerCount=0 for testing scenarios where manual dispatch control is needed
      this.workers = Executors.newFixedThreadPool(1, new DaemonThreadFactory("outbox-dispatcher-"));
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean enqueueHot(QueuedEvent event) {
    if (!accepting.get()) return false;
    boolean enqueued = hotQueue.offer(event);
    metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
    return enqueued;
  }

  public boolean enqueueCold(QueuedEvent event) {
    if (!accepting.get()) return false;
    boolean enqueued = coldQueue.offer(event);
    metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
    return enqueued;
  }

  public boolean hasColdQueueCapacity() {
    return coldQueue.remainingCapacity() > 0;
  }

  private QueuedEvent pollFairly() throws InterruptedException {
    int cycle = pollCounter.getAndIncrement();
    BlockingQueue<QueuedEvent> primary;
    BlockingQueue<QueuedEvent> secondary;
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
    if (failure instanceof UnroutableEventException) {
      markDead(event.envelope().eventId(), failure);
      metrics.incrementDispatchDead();
      logger.log(Level.SEVERE, "Unroutable event marked DEAD: " + event.envelope().eventId(), failure);
      return;
    }

    int nextAttempt = event.attempts() + 1;
    if (nextAttempt >= maxAttempts) {
      markDead(event.envelope().eventId(), failure);
      metrics.incrementDispatchDead();
      logger.log(Level.SEVERE, "Event moved to DEAD after max attempts: " + event.envelope().eventId(), failure);
    } else {
      long delayMs = retryPolicy.computeDelayMs(nextAttempt);
      Instant nextAt = Instant.now().plusMillis(delayMs);
      markRetry(event.envelope().eventId(), nextAt, failure);
      metrics.incrementDispatchFailure();
    }
  }

  private void markDone(String eventId) {
    withConnection("mark DONE", eventId,
        conn -> eventStore.markDone(conn, eventId));
  }

  private void markRetry(String eventId, Instant nextAt, Exception failure) {
    withConnection("mark RETRY", eventId,
        conn -> eventStore.markRetry(conn, eventId, nextAt, failure == null ? null : failure.getMessage()));
  }

  private void markDead(String eventId, Exception failure) {
    withConnection("mark DEAD", eventId,
        conn -> eventStore.markDead(conn, eventId, failure == null ? null : failure.getMessage()));
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

  public static final class Builder {
    private ConnectionProvider connectionProvider;
    private EventStore eventStore;
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

    public Builder connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = connectionProvider;
      return this;
    }

    public Builder eventStore(EventStore eventStore) {
      this.eventStore = eventStore;
      return this;
    }

    public Builder listenerRegistry(ListenerRegistry listenerRegistry) {
      this.listenerRegistry = listenerRegistry;
      return this;
    }

    public Builder inFlightTracker(InFlightTracker inFlightTracker) {
      this.inFlightTracker = inFlightTracker;
      return this;
    }

    public Builder retryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    public Builder maxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder workerCount(int workerCount) {
      this.workerCount = workerCount;
      return this;
    }

    public Builder hotQueueCapacity(int hotQueueCapacity) {
      this.hotQueueCapacity = hotQueueCapacity;
      return this;
    }

    public Builder coldQueueCapacity(int coldQueueCapacity) {
      this.coldQueueCapacity = coldQueueCapacity;
      return this;
    }

    public Builder metrics(MetricsExporter metrics) {
      this.metrics = metrics;
      return this;
    }

    public Builder interceptor(EventInterceptor interceptor) {
      this.interceptors.add(interceptor);
      return this;
    }

    public Builder interceptors(List<EventInterceptor> interceptors) {
      this.interceptors.addAll(interceptors);
      return this;
    }

    public Builder drainTimeoutMs(long drainTimeoutMs) {
      this.drainTimeoutMs = drainTimeoutMs;
      return this;
    }

    public OutboxDispatcher build() {
      return new OutboxDispatcher(this);
    }
  }

}
