package outbox.poller;

import outbox.EventEnvelope;
import outbox.model.OutboxEvent;
import outbox.spi.ConnectionProvider;
import outbox.spi.MetricsExporter;
import outbox.spi.OutboxStore;
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
 * <p>Operates in two modes:
 * <ul>
 *   <li><b>Single-node</b> (default) — uses {@link OutboxStore#pollPending} with no locking.
 *   <li><b>Multi-node</b> — uses {@link OutboxStore#claimPending} with row-level locking.
 *       Enabled via {@link Builder#claimLocking}.
 * </ul>
 *
 * <p>Create instances via {@link #builder()}.
 *
 * <p>This class is thread-safe. The {@link #start()} and {@link #close()} methods are
 * synchronized to prevent concurrent lifecycle transitions.
 *
 * @see OutboxPoller.Builder
 * @see OutboxPollerHandler
 */
public final class OutboxPoller implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(OutboxPoller.class.getName());

    private final ConnectionProvider connectionProvider;
    private final OutboxStore outboxStore;
    private final OutboxPollerHandler handler;
    private final Duration skipRecent;
    private final int batchSize;
    private final long intervalMs;
    private final MetricsExporter metrics;
    private final String ownerId;
    private final Duration lockTimeout;
    private final JsonCodec jsonCodec;

    private ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pollTask;
    private volatile boolean closed;

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
        this.ownerId = builder.ownerId;
        this.lockTimeout = builder.lockTimeout;
        this.jsonCodec = builder.jsonCodec != null ? builder.jsonCodec : JsonCodec.getDefault();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the scheduled polling loop. Subsequent calls are no-ops if already started.
     */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("OutboxPoller has been closed");
        }
        if (pollTask != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("outbox-poller-"));
        pollTask = scheduler.scheduleWithFixedDelay(this::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Executes a single poll cycle. Called automatically by the scheduler, but may also be invoked directly for testing.
     */
    public void poll() {
        if (closed) {
            return;
        }
        try {
            if (handler.availableCapacity() <= 0) {
                return;
            }

            Instant now = Instant.now();
            List<OutboxEvent> rows = fetchPendingRows(now);
            if (rows == null) {
                return; // fetch failed — don't reset lag metric
            }
            if (rows.isEmpty()) {
                metrics.recordOldestLagMs(0);
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

    /**
     * Fetches pending rows from the store. Returns {@code null} on failure
     * (to distinguish from a successful empty result).
     */
    private List<OutboxEvent> fetchPendingRows(Instant now) {
        int effectiveBatch = Math.min(batchSize, handler.availableCapacity());
        if (effectiveBatch <= 0) {
            return List.of();
        }
        try (Connection conn = connectionProvider.getConnection()) {
            if (ownerId != null) {
                // Two-phase claim (UPDATE then SELECT) must run in a single transaction
                conn.setAutoCommit(false);
                try {
                    Instant lockExpiry = now.minus(lockTimeout);
                    List<OutboxEvent> claimed = outboxStore.claimPending(conn, ownerId, now, lockExpiry, skipRecent, effectiveBatch);
                    conn.commit();
                    return claimed;
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    throw e;
                }
            }
            conn.setAutoCommit(true);
            return outboxStore.pollPending(conn, now, skipRecent, effectiveBatch);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to fetch pending outbox rows", e);
            return null;
        }
    }

    private Instant dispatchRows(List<OutboxEvent> rows) {
        // Rows are sorted oldest-first by SQL ORDER BY created_at
        Instant oldest = rows.isEmpty() ? null : rows.get(0).createdAt();
        for (OutboxEvent row : rows) {
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
        Map<String, String> headers = jsonCodec.parseObject(row.headersJson());
        var builder = EventEnvelope.builder(row.eventType())
                .eventId(row.eventId())
                .occurredAt(row.createdAt())
                .aggregateType(row.aggregateType())
                .aggregateId(row.aggregateId())
                .tenantId(row.tenantId())
                .headers(headers)
                .payloadJson(row.payloadJson());
        if (row.availableAt() != null) {
            builder.availableAt(row.availableAt());
        }
        return builder.build();
    }

    private void markDead(String eventId, Exception failure) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(true);
            outboxStore.markDead(conn, eventId, failure == null ? null : failure.getMessage());
        } catch (SQLException | RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
        }
    }

    /**
     * Cancels the polling schedule and shuts down the scheduler thread.
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Builder for {@link OutboxPoller}.
     */
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
        private JsonCodec jsonCodec;

        private Builder() {
        }

        /**
         * Sets the connection provider for obtaining JDBC connections when polling.
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
         * Sets the outbox store used to query and claim pending events.
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
         * Sets the handler that receives polled events (typically a {@link outbox.dispatch.DispatcherPollerHandler}).
         *
         * <p><b>Required.</b>
         *
         * @param handler the callback for processing polled events
         * @return this builder
         */
        public Builder handler(OutboxPollerHandler handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Sets a grace period to skip recently created events during polling.
         *
         * <p>Optional. Defaults to {@link Duration#ZERO}. Must be &ge; 0.
         *
         * @param skipRecent duration to skip recent events
         * @return this builder
         */
        public Builder skipRecent(Duration skipRecent) {
            this.skipRecent = skipRecent;
            return this;
        }

        /**
         * Sets the maximum number of events fetched per poll cycle.
         *
         * <p>Optional. Defaults to {@code 50}. Must be &gt; 0.
         *
         * @param batchSize max events per poll
         * @return this builder
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the polling interval in milliseconds.
         *
         * <p>Optional. Defaults to {@code 5000} ms. Must be &gt; 0.
         *
         * @param intervalMs polling interval in milliseconds
         * @return this builder
         */
        public Builder intervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
            return this;
        }

        /**
         * Sets the metrics exporter for recording poll lag and cold-enqueue counters.
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
         * Enables claim-based locking for multi-node deployments with an auto-generated owner ID.
         *
         * <p>When enabled, the poller uses {@link OutboxStore#claimPending} instead of
         * {@link OutboxStore#pollPending}, acquiring row-level locks so multiple poller
         * instances can safely share the same database.
         *
         * @param lockTimeout how long a claimed event stays locked before it becomes
         *                    eligible for re-claiming by another instance
         * @return this builder
         */
        public Builder claimLocking(Duration lockTimeout) {
            return claimLocking("poller-" + UUID.randomUUID().toString().substring(0, 8), lockTimeout);
        }

        /**
         * Enables claim-based locking for multi-node deployments with an explicit owner ID.
         *
         * <p>When enabled, the poller uses {@link OutboxStore#claimPending} instead of
         * {@link OutboxStore#pollPending}, acquiring row-level locks so multiple poller
         * instances can safely share the same database.
         *
         * @param ownerId     unique identifier for this poller instance (e.g. hostname or pod name)
         * @param lockTimeout how long a claimed event stays locked before it becomes
         *                    eligible for re-claiming by another instance
         * @return this builder
         */
        public Builder claimLocking(String ownerId, Duration lockTimeout) {
            this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
            this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
            if (lockTimeout.isNegative() || lockTimeout.isZero()) {
                throw new IllegalArgumentException("lockTimeout must be positive");
            }
            return this;
        }

        /**
         * Sets a custom JSON codec for decoding event headers from the database.
         *
         * <p>Optional. Defaults to {@link JsonCodec#getDefault()}.
         *
         * @param jsonCodec the JSON codec
         * @return this builder
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
            return this;
        }

        /**
         * Builds the poller. Call {@link OutboxPoller#start()} to begin the polling schedule.
         *
         * @return a new {@link OutboxPoller} instance
         * @throws NullPointerException     if {@code connectionProvider}, {@code outboxStore},
         *                                  or {@code handler} is null
         * @throws IllegalArgumentException if {@code batchSize <= 0}, {@code intervalMs <= 0},
         *                                  or {@code skipRecent} is negative
         */
        public OutboxPoller build() {
            return new OutboxPoller(this);
        }
    }
}
