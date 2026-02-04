package outbox.core.poller;

import outbox.core.api.EventEnvelope;
import outbox.core.api.NoopOutboxMetrics;
import outbox.core.api.OutboxMetrics;
import outbox.core.dispatch.Dispatcher;
import outbox.core.dispatch.QueuedEvent;
import outbox.core.repo.OutboxRepository;
import outbox.core.repo.OutboxRow;
import outbox.core.tx.ConnectionProvider;
import outbox.core.util.JsonCodec;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  private final ConnectionProvider connectionProvider;
  private final OutboxRepository repository;
  private final Dispatcher dispatcher;
  private final Duration skipRecent;
  private final int batchSize;
  private final long intervalMs;
  private final OutboxMetrics metrics;

  private final ScheduledExecutorService scheduler;
  private ScheduledFuture<?> scheduled;

  public OutboxPoller(
      ConnectionProvider connectionProvider,
      OutboxRepository repository,
      Dispatcher dispatcher,
      Duration skipRecent,
      int batchSize,
      long intervalMs,
      OutboxMetrics metrics
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
    this.repository = Objects.requireNonNull(repository, "repository");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.skipRecent = skipRecent == null ? Duration.ZERO : skipRecent;
    this.batchSize = batchSize;
    this.intervalMs = intervalMs;
    this.metrics = metrics == null ? new NoopOutboxMetrics() : metrics;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(new PollerThreadFactory());
  }

  public void start() {
    if (scheduled != null) {
      return;
    }
    scheduled = scheduler.scheduleWithFixedDelay(this::runOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  public void runOnce() {
    try {
      Instant now = Instant.now();
      List<OutboxRow> rows;
      try (Connection conn = connectionProvider.getConnection()) {
        conn.setAutoCommit(true);
        rows = repository.pollPending(conn, now, skipRecent, batchSize);
      } catch (SQLException e) {
        logger.log(Level.SEVERE, "Poller failed to query outbox", e);
        return;
      }

      if (rows.isEmpty()) {
        return;
      }

      Instant oldest = rows.get(0).createdAt();
      for (OutboxRow row : rows) {
        if (row.createdAt().isBefore(oldest)) {
          oldest = row.createdAt();
        }
        EventEnvelope envelope;
        try {
          envelope = toEnvelope(row);
        } catch (RuntimeException ex) {
          logger.log(Level.SEVERE, "Failed to decode outbox row eventId=" + row.eventId(), ex);
          markDead(row.eventId(), ex);
          continue;
        }
        boolean enqueued = dispatcher.enqueueCold(new QueuedEvent(envelope, QueuedEvent.Source.COLD, row.attempts()));
        if (enqueued) {
          metrics.incrementColdEnqueued();
        } else {
          break;
        }
      }
      long lagMs = Math.max(0L, Duration.between(oldest, now).toMillis());
      metrics.recordOldestLagMs(lagMs);
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Poller run failed", t);
    }
  }

  private EventEnvelope toEnvelope(OutboxRow row) {
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
      repository.markDead(conn, eventId, failure == null ? null : failure.getMessage());
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
    }
  }

  @Override
  public void close() {
    if (scheduled != null) {
      scheduled.cancel(false);
    }
    scheduler.shutdownNow();
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
