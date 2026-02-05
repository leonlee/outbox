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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OutboxDispatcher implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(OutboxDispatcher.class.getName());

  private static final long QUEUE_POLL_TIMEOUT_MS = 50;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

  private final BlockingQueue<QueuedEvent> hotQueue;
  private final BlockingQueue<QueuedEvent> coldQueue;
  private final ExecutorService workers;
  private final AtomicBoolean running = new AtomicBoolean(true);

  private final ConnectionProvider connectionProvider;
  private final EventStore eventStore;
  private final ListenerRegistry listenerRegistry;
  private final InFlightTracker inFlightTracker;
  private final RetryPolicy retryPolicy;
  private final int maxAttempts;
  private final MetricsExporter metrics;

  public OutboxDispatcher(
      ConnectionProvider connectionProvider,
      EventStore eventStore,
      ListenerRegistry listenerRegistry,
      InFlightTracker inFlightTracker,
      RetryPolicy retryPolicy,
      int maxAttempts,
      int workerCount,
      int hotQueueCapacity,
      int coldQueueCapacity,
      MetricsExporter metrics
  ) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (workerCount < 0) {
      throw new IllegalArgumentException("workerCount must be >= 0");
    }
    if (hotQueueCapacity <= 0 || coldQueueCapacity <= 0) {
      throw new IllegalArgumentException("Queue capacities must be > 0");
    }
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.listenerRegistry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.inFlightTracker = Objects.requireNonNull(inFlightTracker, "inFlightTracker");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    this.maxAttempts = maxAttempts;
    this.metrics = metrics == null ? MetricsExporter.NOOP : metrics;

    this.hotQueue = new ArrayBlockingQueue<>(hotQueueCapacity);
    this.coldQueue = new ArrayBlockingQueue<>(coldQueueCapacity);

    // Create thread pool with minimum 1 thread (required by ExecutorService),
    // but only start workers if workerCount > 0. This allows workerCount=0
    // for testing scenarios where manual dispatch control is needed.
    int threadCount = Math.max(1, workerCount);
    this.workers = Executors.newFixedThreadPool(threadCount, new DispatcherThreadFactory());

    for (int i = 0; i < workerCount; i++) {
      workers.submit(this::workerLoop);
    }
  }

  public boolean enqueueHot(QueuedEvent event) {
    boolean enqueued = hotQueue.offer(event);
    metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
    return enqueued;
  }

  public boolean enqueueCold(QueuedEvent event) {
    boolean enqueued = coldQueue.offer(event);
    metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
    return enqueued;
  }

  public boolean hasColdQueueCapacity() {
    return coldQueue.remainingCapacity() > 0;
  }

  private void workerLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        QueuedEvent event = hotQueue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (event == null) {
          event = coldQueue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        if (event == null) {
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
    List<EventListener> listeners = listenerRegistry.listenersFor(envelope.eventType());
    for (EventListener listener : listeners) {
      listener.onEvent(envelope);
    }
  }

  private void handleFailure(QueuedEvent event, Exception failure) {
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
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      eventStore.markDone(conn, eventId);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to mark DONE for eventId=" + eventId, e);
    }
  }

  private void markRetry(String eventId, Instant nextAt, Exception failure) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      eventStore.markRetry(conn, eventId, nextAt, failure == null ? null : failure.getMessage());
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to mark RETRY for eventId=" + eventId, e);
    }
  }

  private void markDead(String eventId, Exception failure) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      eventStore.markDead(conn, eventId, failure == null ? null : failure.getMessage());
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
    }
  }

  @Override
  public void close() {
    running.set(false);
    workers.shutdownNow();
    try {
      workers.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class DispatcherThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "outbox-dispatcher-" + counter.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    }
  }
}
