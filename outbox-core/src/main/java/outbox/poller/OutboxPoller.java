package outbox.poller;

import outbox.EventEnvelope;
import outbox.model.OutboxEvent;
import outbox.spi.ConnectionProvider;
import outbox.spi.OutboxStore;
import outbox.spi.MetricsExporter;
import outbox.util.DaemonThreadFactory;
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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduled database scanner that polls for pending outbox events as a fallback
 * when the hot path is unavailable or events are dropped.
 *
 * <p>Supports claim-based locking via {@code ownerId}/{@code lockTimeout} for safe
 * multi-instance deployments. Create instances via {@link #builder()}.
 *
 * <p>This class is thread-safe. The {@link #start()} and {@link #close()} methods are
 * synchronized to prevent concurrent lifecycle transitions.
 *
 * @see OutboxPoller.Builder
 * @see OutboxPollerHandler
 */
public final class OutboxPoller implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(OutboxPoller.class.getName());
  private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofMinutes(5);

  private final ConnectionProvider connectionProvider;
  private final OutboxStore outboxStore;
  private final OutboxPollerHandler handler;
  private final Duration skipRecent;
  private final int batchSize;
  private final long intervalMs;
  private final MetricsExporter metrics;
  private final String ownerId;
  private final Duration lockTimeout;

  private final ScheduledExecutorService scheduler;
  private volatile ScheduledFuture<?> pollTask;

  private OutboxPoller(Builder builder) {
    this.connectionProvider = Objects.requireNonNull(builder.connectionProvider, "connectionProvider");
    this.outboxStore = Objects.requireNonNull(builder.outboxStore, "outboxStore");
    this.handler = Objects.requireNonNull(builder.handler, "handler");

    int batchSize = builder.batchSize;
    long intervalMs = builder.intervalMs;
    Duration skipRecent = builder.skipRecent;

    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be > 0");
    }
    if (intervalMs <= 0L) {
      throw new IllegalArgumentException("intervalMs must be > 0");
    }
    if (skipRecent != null && skipRecent.isNegative()) {
      throw new IllegalArgumentException("skipRecent must be >= 0");
    }

    this.skipRecent = skipRecent == null ? Duration.ZERO : skipRecent;
    this.batchSize = batchSize;
    this.intervalMs = intervalMs;
    this.metrics = builder.metrics != null ? builder.metrics : MetricsExporter.NOOP;
    this.ownerId = builder.ownerId != null
        ? builder.ownerId
        : (builder.lockTimeout != null ? "poller-" + UUID.randomUUID().toString().substring(0, 8) : null);
    this.lockTimeout = builder.lockTimeout != null ? builder.lockTimeout : DEFAULT_LOCK_TIMEOUT;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("outbox-poller-"));
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Starts the scheduled polling loop. Subsequent calls are no-ops if already started.
   */
  public synchronized void start() {
    if (pollTask != null) {
      return;
    }
    pollTask = scheduler.scheduleWithFixedDelay(this::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  /** Executes a single poll cycle. Called automatically by the scheduler, but may also be invoked directly for testing. */
  public void poll() {
    try {
      if (!handler.hasCapacity()) {
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
        return outboxStore.claimPending(conn, ownerId, now, lockExpiry, skipRecent, batchSize);
      }
      return outboxStore.pollPending(conn, now, skipRecent, batchSize);
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

    boolean accepted = handler.handle(envelope, row.attempts());
    if (accepted) {
      metrics.incrementColdEnqueued();
    }
    return accepted;
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
      outboxStore.markDead(conn, eventId, failure == null ? null : failure.getMessage());
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
    }
  }

  /** Cancels the polling schedule and shuts down the scheduler thread. */
  @Override
  public synchronized void close() {
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

  public static final class Builder {
    private ConnectionProvider connectionProvider;
    private OutboxStore outboxStore;
    private OutboxPollerHandler handler;
    private Duration skipRecent;
    private int batchSize = 50;
    private long intervalMs = 5000;
    private MetricsExporter metrics;
    private String ownerId;
    private Duration lockTimeout;

    private Builder() {}

    public Builder connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = connectionProvider;
      return this;
    }

    public Builder outboxStore(OutboxStore outboxStore) {
      this.outboxStore = outboxStore;
      return this;
    }

    public Builder handler(OutboxPollerHandler handler) {
      this.handler = handler;
      return this;
    }

    public Builder skipRecent(Duration skipRecent) {
      this.skipRecent = skipRecent;
      return this;
    }

    public Builder batchSize(int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Builder intervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
      return this;
    }

    public Builder metrics(MetricsExporter metrics) {
      this.metrics = metrics;
      return this;
    }

    public Builder ownerId(String ownerId) {
      this.ownerId = ownerId;
      return this;
    }

    public Builder lockTimeout(Duration lockTimeout) {
      this.lockTimeout = lockTimeout;
      return this;
    }

    public OutboxPoller build() {
      return new OutboxPoller(this);
    }
  }
}
