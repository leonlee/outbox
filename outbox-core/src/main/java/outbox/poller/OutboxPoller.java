package outbox.poller;

import outbox.EventEnvelope;
import outbox.dispatch.OutboxDispatcher;
import outbox.dispatch.QueuedEvent;
import outbox.model.OutboxEvent;
import outbox.spi.ConnectionProvider;
import outbox.spi.EventStore;
import outbox.spi.MetricsExporter;
import outbox.util.JsonCodec;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OutboxPoller implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(OutboxPoller.class.getName());
  private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(5);

  private final ConnectionProvider connectionProvider;
  private final EventStore eventStore;
  private final OutboxDispatcher dispatcher;
  private final Duration skipRecent;
  private final int batchSize;
  private final long intervalMs;
  private final MetricsExporter metrics;
  private final String ownerId;
  private final Duration lockTimeout;

  private final ScheduledExecutorService scheduler;
  private volatile ScheduledFuture<?> pollTask;

  public OutboxPoller(
      ConnectionProvider connectionProvider,
      EventStore eventStore,
      OutboxDispatcher dispatcher,
      Duration skipRecent,
      int batchSize,
      long intervalMs,
      MetricsExporter metrics
  ) {
    this(connectionProvider, eventStore, dispatcher, skipRecent,
        batchSize, intervalMs, metrics, null, null);
  }

  public OutboxPoller(
      ConnectionProvider connectionProvider,
      EventStore eventStore,
      OutboxDispatcher dispatcher,
      Duration skipRecent,
      int batchSize,
      long intervalMs,
      MetricsExporter metrics,
      String ownerId,
      Duration lockTimeout
  ) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be > 0");
    }
    if (intervalMs <= 0L) {
      throw new IllegalArgumentException("intervalMs must be > 0");
    }
    if (skipRecent != null && skipRecent.isNegative()) {
      throw new IllegalArgumentException("skipRecent must be >= 0");
    }
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.skipRecent = skipRecent == null ? Duration.ZERO : skipRecent;
    this.batchSize = batchSize;
    this.intervalMs = intervalMs;
    this.metrics = metrics == null ? MetricsExporter.NOOP : metrics;
    this.ownerId = ownerId != null
        ? ownerId
        : (lockTimeout != null ? "poller-" + UUID.randomUUID().toString().substring(0, 8) : null);
    this.lockTimeout = lockTimeout != null ? lockTimeout : DEFAULT_LOCK_TIMEOUT;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(new PollerThreadFactory());
  }

  public synchronized void start() {
    if (pollTask != null) {
      return;
    }
    pollTask = scheduler.scheduleWithFixedDelay(this::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  public void poll() {
    try {
      if (!dispatcher.hasColdQueueCapacity()) {
        return;
      }

      Instant now = Instant.now();
      List<OutboxEvent> rows = fetchPendingRows(now);
      if (rows.isEmpty()) {
        return;
      }

      Instant oldest = dispatchRows(rows);
      if (oldest != null) {
        long lagMs = Duration.between(oldest, now).toMillis();
        metrics.recordOldestLagMs(Math.max(0L, lagMs));
      }
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Poll cycle failed", t);
    }
  }

  private List<OutboxEvent> fetchPendingRows(Instant now) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      if (ownerId != null) {
        Instant lockExpiry = now.minus(lockTimeout);
        return eventStore.claimPending(conn, ownerId, now, lockExpiry, skipRecent, batchSize);
      }
      return eventStore.pollPending(conn, now, skipRecent, batchSize);
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to fetch pending outbox rows", e);
      return List.of();
    }
  }

  private Instant dispatchRows(List<OutboxEvent> rows) {
    Instant oldest = null;
    for (OutboxEvent row : rows) {
      if (oldest == null || row.createdAt().isBefore(oldest)) {
        oldest = row.createdAt();
      }
      if (!dispatchRow(row)) {
        break; // cold queue full
      }
    }
    return oldest;
  }

  private boolean dispatchRow(OutboxEvent row) {
    EventEnvelope envelope;
    try {
      envelope = convertToEnvelope(row);
    } catch (RuntimeException e) {
      logger.log(Level.SEVERE, "Failed to decode outbox row eventId=" + row.eventId(), e);
      markDead(row.eventId(), e);
      return true; // continue processing other rows
    }

    QueuedEvent event = new QueuedEvent(envelope, QueuedEvent.Source.COLD, row.attempts());
    boolean enqueued = dispatcher.enqueueCold(event);
    if (enqueued) {
      metrics.incrementColdEnqueued();
    }
    return enqueued;
  }

  private EventEnvelope convertToEnvelope(OutboxEvent row) {
    Map<String, String> headers = JsonCodec.parseObject(row.headersJson());
    return EventEnvelope.builder(row.eventType())
        .eventId(row.eventId())
        .occurredAt(row.createdAt())
        .aggregateType(row.aggregateType())
        .aggregateId(row.aggregateId())
        .tenantId(row.tenantId())
        .headers(headers)
        .payloadJson(row.payloadJson())
        .build();
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
    if (pollTask != null) {
      pollTask.cancel(false);
    }
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class PollerThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "outbox-poller-" + counter.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    }
  }
}
