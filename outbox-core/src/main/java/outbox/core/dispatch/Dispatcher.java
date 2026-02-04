package outbox.core.dispatch;

import outbox.core.api.EventEnvelope;
import outbox.core.api.NoopOutboxMetrics;
import outbox.core.api.OutboxMetrics;
import outbox.core.registry.Handler;
import outbox.core.registry.HandlerRegistry;
import outbox.core.registry.Publisher;
import outbox.core.registry.PublisherRegistry;
import outbox.core.repo.OutboxRepository;
import outbox.core.tx.ConnectionProvider;

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

public final class Dispatcher implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(Dispatcher.class.getName());

  private final BlockingQueue<QueuedEvent> hotQueue;
  private final BlockingQueue<QueuedEvent> coldQueue;
  private final ExecutorService workers;
  private final AtomicBoolean running = new AtomicBoolean(true);

  private final ConnectionProvider connectionProvider;
  private final OutboxRepository repository;
  private final PublisherRegistry publisherRegistry;
  private final HandlerRegistry handlerRegistry;
  private final InFlightTracker inFlightTracker;
  private final RetryPolicy retryPolicy;
  private final int maxAttempts;
  private final OutboxMetrics metrics;

  public Dispatcher(
      ConnectionProvider connectionProvider,
      OutboxRepository repository,
      PublisherRegistry publisherRegistry,
      HandlerRegistry handlerRegistry,
      InFlightTracker inFlightTracker,
      RetryPolicy retryPolicy,
      int maxAttempts,
      int workerCount,
      int hotQueueCapacity,
      int coldQueueCapacity,
      OutboxMetrics metrics
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
    this.repository = Objects.requireNonNull(repository, "repository");
    this.publisherRegistry = Objects.requireNonNull(publisherRegistry, "publisherRegistry");
    this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
    this.inFlightTracker = Objects.requireNonNull(inFlightTracker, "inFlightTracker");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    this.maxAttempts = maxAttempts;
    this.metrics = metrics == null ? new NoopOutboxMetrics() : metrics;

    this.hotQueue = new ArrayBlockingQueue<>(hotQueueCapacity);
    this.coldQueue = new ArrayBlockingQueue<>(coldQueueCapacity);
    int threadCount = Math.max(1, workerCount);
    this.workers = Executors.newFixedThreadPool(threadCount, new DispatcherThreadFactory());

    int startCount = Math.max(0, workerCount);
    for (int i = 0; i < startCount; i++) {
      workers.submit(this::runLoop);
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

  private void runLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        QueuedEvent event = hotQueue.poll(50, TimeUnit.MILLISECONDS);
        if (event == null) {
          event = coldQueue.poll(50, TimeUnit.MILLISECONDS);
        }
        if (event == null) {
          continue;
        }
        process(event);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Dispatcher loop error", t);
      }
    }
  }

  private void process(QueuedEvent event) {
    String eventId = event.envelope().eventId();
    if (!inFlightTracker.tryAcquire(eventId)) {
      return;
    }
    try {
      executeHandlers(event.envelope());
      markDone(eventId);
      metrics.incrementDispatchSuccess();
    } catch (Exception e) {
      handleFailure(event, e);
    } finally {
      inFlightTracker.release(eventId);
    }
  }

  private void executeHandlers(EventEnvelope envelope) throws Exception {
    List<Publisher> publishers = publisherRegistry.publishersFor(envelope.eventType());
    for (Publisher publisher : publishers) {
      publisher.publish(envelope);
    }

    List<Handler> handlers = handlerRegistry.handlersFor(envelope.eventType());
    for (Handler handler : handlers) {
      handler.handle(envelope);
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
      repository.markDone(conn, eventId);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to mark DONE for eventId=" + eventId, e);
    }
  }

  private void markRetry(String eventId, Instant nextAt, Exception failure) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      repository.markRetry(conn, eventId, nextAt, failure == null ? null : failure.getMessage());
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to mark RETRY for eventId=" + eventId, e);
    }
  }

  private void markDead(String eventId, Exception failure) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      repository.markDead(conn, eventId, failure == null ? null : failure.getMessage());
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
    }
  }

  @Override
  public void close() {
    running.set(false);
    workers.shutdownNow();
    try {
      workers.awaitTermination(5, TimeUnit.SECONDS);
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
