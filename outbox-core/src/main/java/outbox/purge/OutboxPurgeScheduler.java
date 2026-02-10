package outbox.purge;

import outbox.spi.ConnectionProvider;
import outbox.spi.EventPurger;
import outbox.util.DaemonThreadFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduled component that purges terminal outbox events (DONE and DEAD) older
 * than a configurable retention period.
 *
 * <p>Modeled after {@link outbox.poller.OutboxPoller}: builder pattern,
 * {@link AutoCloseable}, daemon threads, synchronized lifecycle.
 *
 * <p>Each purge cycle deletes in batches (default 500) until fewer than
 * {@code batchSize} rows are deleted, then sleeps until the next interval.
 * Each batch uses its own auto-committed connection to limit lock duration.
 *
 * <p>Create instances via {@link #builder()}.
 *
 * @see OutboxPurgeScheduler.Builder
 * @see EventPurger
 */
public final class OutboxPurgeScheduler implements AutoCloseable {
  private static final Logger logger = Logger.getLogger(OutboxPurgeScheduler.class.getName());

  private final ConnectionProvider connectionProvider;
  private final EventPurger purger;
  private final Duration retention;
  private final int batchSize;
  private final long intervalSeconds;

  private final ScheduledExecutorService scheduler;
  private volatile ScheduledFuture<?> purgeTask;

  private OutboxPurgeScheduler(Builder builder) {
    this.connectionProvider = Objects.requireNonNull(builder.connectionProvider, "connectionProvider");
    this.purger = Objects.requireNonNull(builder.purger, "purger");

    if (builder.retention != null && builder.retention.isNegative()) {
      throw new IllegalArgumentException("retention must be >= 0");
    }
    if (builder.batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be > 0");
    }
    if (builder.intervalSeconds <= 0L) {
      throw new IllegalArgumentException("intervalSeconds must be > 0");
    }

    this.retention = builder.retention != null ? builder.retention : Duration.ofDays(7);
    this.batchSize = builder.batchSize;
    this.intervalSeconds = builder.intervalSeconds;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(
        new DaemonThreadFactory("outbox-purge-"));
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Starts the scheduled purge loop. Subsequent calls are no-ops if already started.
   */
  public synchronized void start() {
    if (purgeTask != null) {
      return;
    }
    purgeTask = scheduler.scheduleWithFixedDelay(
        this::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
  }

  /**
   * Executes a single purge cycle, deleting batches until fewer than
   * {@code batchSize} rows are deleted. Each batch uses its own connection.
   *
   * <p>May be invoked directly for testing or one-off purges.
   */
  public void runOnce() {
    try {
      Instant cutoff = Instant.now().minus(retention);
      int totalDeleted = 0;
      int deleted;
      do {
        deleted = purgeBatch(cutoff);
        totalDeleted += deleted;
      } while (deleted >= batchSize);
      if (totalDeleted > 0) {
        logger.log(Level.INFO, "Purged {0} terminal events older than {1}",
            new Object[]{totalDeleted, cutoff});
      }
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Purge cycle failed", t);
    }
  }

  private int purgeBatch(Instant cutoff) {
    try (Connection conn = connectionProvider.getConnection()) {
      conn.setAutoCommit(true);
      return purger.purge(conn, cutoff, batchSize);
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to obtain connection for purge", e);
      return 0;
    }
  }

  /** Cancels the purge schedule and shuts down the scheduler thread. */
  @Override
  public synchronized void close() {
    if (purgeTask != null) {
      purgeTask.cancel(false);
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
    private EventPurger purger;
    private Duration retention;
    private int batchSize = 500;
    private long intervalSeconds = 3600;

    private Builder() {}

    public Builder connectionProvider(ConnectionProvider connectionProvider) {
      this.connectionProvider = connectionProvider;
      return this;
    }

    public Builder purger(EventPurger purger) {
      this.purger = purger;
      return this;
    }

    public Builder retention(Duration retention) {
      this.retention = retention;
      return this;
    }

    public Builder batchSize(int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Builder intervalSeconds(long intervalSeconds) {
      this.intervalSeconds = intervalSeconds;
      return this;
    }

    public OutboxPurgeScheduler build() {
      return new OutboxPurgeScheduler(this);
    }
  }
}
