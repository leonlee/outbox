package io.outbox;

import io.outbox.dispatch.DefaultInFlightTracker;
import io.outbox.dispatch.DispatcherPollerHandler;
import io.outbox.dispatch.DispatcherWriterHook;
import io.outbox.dispatch.EventInterceptor;
import io.outbox.dispatch.OutboxDispatcher;
import io.outbox.dispatch.RetryPolicy;
import io.outbox.poller.OutboxPoller;
import io.outbox.purge.OutboxPurgeScheduler;
import io.outbox.registry.ListenerRegistry;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.EventPurger;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;

import java.util.logging.Logger;
import java.util.logging.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composite entry point that wires an {@link OutboxDispatcher}, {@link OutboxPoller},
 * and {@link OutboxWriter} into a single {@link AutoCloseable} unit.
 *
 * <p>Four scenario-specific builders expose only the parameters relevant to each
 * deployment topology:
 * <ul>
 *   <li>{@link #singleNode()} — hot path + poller fallback (default)</li>
 *   <li>{@link #multiNode()} — hot path + poller with claim-based locking</li>
 *   <li>{@link #ordered()} — poller-only, single worker, no retry</li>
 *   <li>{@link #writerOnly()} — writer only, no dispatcher/poller (CDC mode)</li>
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
    private static final Logger LOGGER = Logger.getLogger(Outbox.class.getName());

    private final OutboxWriter writer;
    private final OutboxPoller poller;
    private final OutboxDispatcher dispatcher;
    private final OutboxPurgeScheduler purgeScheduler;
    private final MetricsExporter metrics;
    private volatile boolean started;
    private boolean closed; // guarded by this

    private Outbox(OutboxWriter writer, OutboxPoller poller,
                   OutboxDispatcher dispatcher, OutboxPurgeScheduler purgeScheduler,
                   MetricsExporter metrics) {
        this.writer = writer;
        this.poller = poller;
        this.dispatcher = dispatcher;
        this.purgeScheduler = purgeScheduler;
        this.metrics = metrics;
    }

    /**
     * Starts the poller. Called automatically by {@code build()} unless
     * {@code deferStart(true)} was set on the builder.
     *
     * <p>In Spring Boot, this is called by {@code OutboxLifecycle} (a
     * {@code SmartLifecycle} bean) after all listeners are registered,
     * guaranteeing no startup race.
     *
     * <p>Thread-safe and idempotent: concurrent and repeated calls start the poller once.
     *
     * <p>Not restartable. After {@link #close()}, a call on an outbox that had already started
     * returns without doing anything; on one that never started, a poller (if there is one)
     * refuses with {@link IllegalStateException}. Writer-only outboxes have no poller to refuse.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        if (poller != null) {
            poller.start();
        }
        if (purgeScheduler != null) {
            // Deliberately after the poller, and deliberately not fatal. Purging is housekeeping;
            // delivery is the job. Letting a failure here abort start() would leave an outbox that
            // accepts writes and never dispatches them because it could not schedule a cleanup.
            try {
                purgeScheduler.start();
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE,
                        "Purge scheduler failed to start; events will be delivered but not purged", e);
            }
        }
        started = true;
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
     * Shuts down components in order: purge scheduler, poller, dispatcher.
     * Null components (e.g. in writer-only mode) are skipped.
     *
     * <p>Idempotent, and a call that overlaps one already in progress waits for it to finish
     * rather than returning early — a caller that tears down the {@code DataSource} next must
     * not do so while the dispatcher is still draining. A Spring context can close the outbox
     * twice (lifecycle stop, then the bean's destroy method), and a JVM shutdown hook can race
     * either.
     *
     * <p>Every component is closed even if an earlier one fails. Each failure is logged at
     * SEVERE; the first is rethrown with the rest attached as suppressed.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException first = null;
        if (purgeScheduler != null) {
            try {
                purgeScheduler.close();
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Failed to close purge scheduler", e);
                first = e;
            }
        }
        if (poller != null) {
            try {
                poller.close();
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Failed to close poller", e);
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (dispatcher != null) {
            try {
                dispatcher.close();
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Failed to close dispatcher", e);
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (metrics instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                RuntimeException re = (e instanceof RuntimeException r) ? r : new RuntimeException(e);
                LOGGER.log(Level.SEVERE, "Failed to close metrics exporter", e);
                if (first == null) {
                    first = re;
                } else {
                    first.addSuppressed(re);
                }
            }
        }
        if (first != null) {
            throw first;
        }
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

    /**
     * Creates a builder for writer-only (CDC) mode: no dispatcher or poller.
     *
     * <p>Events are written to the outbox table and consumed externally (e.g. via
     * Debezium reading the WAL/binlog). An optional age-based purge scheduler
     * can be configured to clean up old events.
     *
     * @return a new writer-only builder
     */
    public static WriterOnlyBuilder writerOnly() {
        return new WriterOnlyBuilder();
    }

    // ── Abstract builder ─────────────────────────────────────────────

    /**
     * Base builder with shared required and optional parameters.
     *
     * @param <B> the concrete builder type (CRTP)
     */
    // Builder fields intentionally share their setter names — that is the builder idiom,
    // and PMD's AvoidFieldNameMatchingMethodName has no exception for it. Suppressed at the
    // class so the rule stops reporting the whole builder every time one field is touched.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public abstract static sealed class AbstractBuilder<B extends AbstractBuilder<B>>
            permits SingleNodeBuilder, MultiNodeBuilder, OrderedBuilder, WriterOnlyBuilder {

        ConnectionProvider connectionProvider;
        TxContext txContext;
        OutboxStore outboxStore;
        ListenerRegistry listenerRegistry;
        MetricsExporter metrics;
        final List<EventInterceptor> interceptors = new ArrayList<>();
        long intervalMs = 5000;
        int batchSize = 50;
        Duration skipRecent;
        long drainTimeoutMs = 5000;
        long hotTripMs = 0;
        long inFlightTtlMs = 0;
        boolean suppressReplays = false;
        EventPurger purger;
        Duration purgeRetention;
        int purgeBatchSize = 500;
        long purgeIntervalSeconds = 3600;
        boolean deferStart = false;
        private final AtomicBoolean built = new AtomicBoolean(false);

        AbstractBuilder() {
        }

        /**
         * Marks this builder as used, preventing reuse.
         *
         * @throws IllegalStateException if build() was already called
         */
        void markBuilt() {
            if (!built.compareAndSet(false, true)) {
                throw new IllegalStateException("build() already called on this builder");
            }
        }

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
         * Appends a single event interceptor for before/after dispatch hooks.
         *
         * @param interceptor the interceptor to add
         * @return this builder
         */
        public B interceptor(EventInterceptor interceptor) {
            Objects.requireNonNull(interceptor, "interceptor");
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
            Objects.requireNonNull(interceptors, "interceptors");
            for (int i = 0; i < interceptors.size(); i++) {
                if (interceptors.get(i) == null) {
                    throw new NullPointerException("interceptors[" + i + "] is null");
                }
            }
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

        /**
         * Head-of-line age above which the hot path stops accepting and lets the poller deliver
         * instead. {@code 0} (the default) disables the breaker.
         *
         * <p>Only meaningful together with {@code skipRecent}: keep this below it, since the hot
         * copy is only a head start if the event reaches a terminal state before the poller may
         * claim the row. See
         * {@link io.outbox.dispatch.OutboxDispatcher.Builder#hotTripMs(long)}.
         *
         * @param hotTripMs age in milliseconds, or 0 to disable
         * @return this builder
         */
        public B hotTripMs(long hotTripMs) {
            this.hotTripMs = hotTripMs;
            return self();
        }

        /**
         * TTL for {@link io.outbox.dispatch.DefaultInFlightTracker} entries.
         * {@code 0} (the default) means entries live until explicitly released.
         *
         * <p>Required by {@link #suppressReplays(boolean)}, which deliberately does not release
         * settled events.
         *
         * @param inFlightTtlMs entry time-to-live in milliseconds, or 0 for none
         * @return this builder
         */
        public B inFlightTtlMs(long inFlightTtlMs) {
            this.inFlightTtlMs = inFlightTtlMs;
            return self();
        }

        /**
         * Keeps terminal (DONE / DEAD) events in the in-flight tracker until the TTL expires them,
         * so a second copy of an already-settled event is rejected instead of re-delivered. See
         * {@link io.outbox.dispatch.OutboxDispatcher.Builder#suppressReplays(boolean)}.
         *
         * @param suppressReplays whether settled events stay tracked until TTL
         * @return this builder
         */
        public B suppressReplays(boolean suppressReplays) {
            this.suppressReplays = suppressReplays;
            return self();
        }

        /**
         * Enables periodic deletion of events that are no longer needed.
         *
         * <p>Pick the implementation to match how events reach a terminal state. With a dispatcher
         * running, that is {@code status IN (DONE, DEAD)} — use an
         * {@code AbstractJdbcEventPurger}. In CDC / writer-only setups nothing marks events DONE,
         * so age is the only safe criterion and {@code AbstractJdbcAgeBasedPurger} is correct.
         * Using the age-based purger where a dispatcher IS running would delete undelivered events.
         *
         * <p>Optional. Without it, terminal events accumulate forever.
         *
         * @param purger the purger implementation
         * @return this builder
         */
        public B purger(EventPurger purger) {
            this.purger = purger;
            return self();
        }

        /**
         * How long a terminal event is kept before it becomes eligible for deletion.
         *
         * @param purgeRetention retention duration
         * @return this builder
         */
        public B purgeRetention(Duration purgeRetention) {
            this.purgeRetention = purgeRetention;
            return self();
        }

        /**
         * Rows deleted per statement. The scheduler repeats within a cycle until a batch comes back
         * short, so this bounds the size of a single DELETE, not the work done per cycle.
         *
         * @param purgeBatchSize max rows per statement
         * @return this builder
         */
        public B purgeBatchSize(int purgeBatchSize) {
            this.purgeBatchSize = purgeBatchSize;
            return self();
        }

        /**
         * Seconds between purge cycles.
         *
         * @param purgeIntervalSeconds interval in seconds
         * @return this builder
         */
        public B purgeIntervalSeconds(long purgeIntervalSeconds) {
            this.purgeIntervalSeconds = purgeIntervalSeconds;
            return self();
        }

        /**
         * Builds the purge scheduler, or returns {@code null} if no purger was set.
         *
         * <p>Honours {@link #deferStart(boolean)}: purging is background work, so under a deferred
         * start it waits for {@link Outbox#start()} along with the poller rather than beginning
         * during bean creation. That matters on an environment carrying a backlog, where the first
         * cycle is not a trickle — it drains until a batch comes back short, and doing that while
         * the context is still coming up competes with startup for the database.
         */
        OutboxPurgeScheduler buildPurgeScheduler() {
            if (purger == null) {
                return null;
            }
            OutboxPurgeScheduler.Builder pb = OutboxPurgeScheduler.builder()
                    .connectionProvider(connectionProvider)
                    .purger(purger)
                    .batchSize(purgeBatchSize)
                    .intervalSeconds(purgeIntervalSeconds);
            if (purgeRetention != null) {
                pb.retention(purgeRetention);
            }
            OutboxPurgeScheduler scheduler = pb.build();
            if (deferStart) {
                return scheduler;
            }
            try {
                scheduler.start();
            } catch (RuntimeException e) {
                // Same policy as Outbox.start(), which handles the deferred path: purging is
                // housekeeping and delivery is the job. Rethrowing here made the non-deferred
                // builders fail build() — tearing down a poller and dispatcher that had already
                // started — over a cleanup task the deferred path would merely have logged.
                LOGGER.log(Level.SEVERE,
                        "Purge scheduler failed to start; events will be delivered but not purged", e);
            }
            return scheduler;
        }

        /**
         * Defers poller startup until {@link Outbox#start()} is called explicitly.
         *
         * <p>When {@code true}, {@code build()} creates all components but does not
         * start the poller. Use this in Spring Boot with {@code SmartLifecycle} to
         * ensure listeners are registered before polling begins.
         *
         * <p>Optional. Defaults to {@code false} (auto-start on build).
         *
         * @param deferStart whether to defer poller startup
         * @return this builder
         */
        public B deferStart(boolean deferStart) {
            this.deferStart = deferStart;
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

            markBuilt();
            OutboxDispatcher.Builder db = OutboxDispatcher.builder()
                    .connectionProvider(connectionProvider)
                    .outboxStore(outboxStore)
                    .listenerRegistry(listenerRegistry)
                    .workerCount(workerCount)
                    .hotQueueCapacity(hotQueueCapacity)
                    .coldQueueCapacity(coldQueueCapacity)
                    .maxAttempts(maxAttempts)
                    .drainTimeoutMs(drainTimeoutMs)
                    .hotTripMs(hotTripMs)
                    .suppressReplays(suppressReplays)
                    .hotPathEnabled(hotPathEnabled)
                    .interceptors(interceptors);
            if (inFlightTtlMs > 0) {
                db.inFlightTracker(new DefaultInFlightTracker(inFlightTtlMs));
            }
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
                if (ownerId != null) {
                    pb.claimLocking(ownerId, lockTimeout);
                }
                poller = pb.build();
            } catch (RuntimeException e) {
                dispatcher.close();
                throw e;
            }
            if (!deferStart) {
                try {
                    poller.start();
                } catch (RuntimeException e) {
                    poller.close();
                    dispatcher.close();
                    throw e;
                }
            }

            OutboxWriter writer;
            if (hotPathEnabled) {
                WriterHook writerHook = new DispatcherWriterHook(dispatcher, metrics);
                writer = new DefaultOutboxWriter(txContext, outboxStore, writerHook);
            } else {
                writer = new DefaultOutboxWriter(txContext, outboxStore);
            }

            OutboxPurgeScheduler purgeScheduler;
            try {
                purgeScheduler = buildPurgeScheduler();
            } catch (RuntimeException e) {
                poller.close();
                dispatcher.close();
                throw e;
            }
            return new Outbox(writer, poller, dispatcher, purgeScheduler, metrics);
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
    // Builder fields intentionally share their setter names — that is the builder idiom,
    // and PMD's AvoidFieldNameMatchingMethodName has no exception for it. Suppressed at the
    // class so the rule stops reporting the whole builder every time one field is touched.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public static final class SingleNodeBuilder extends AbstractBuilder<SingleNodeBuilder> {
        private int workerCount = 4;
        private int hotQueueCapacity = 1000;
        private boolean hotPathEnabled = true;
        private int coldQueueCapacity = 1000;
        private int maxAttempts = 10;
        private RetryPolicy retryPolicy;

        SingleNodeBuilder() {
        }

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
         * Whether after-commit events are dispatched from memory. On by default.
         *
         * <p>Off makes the poller the only delivery path: no writer hook is installed, so an event
         * is dispatched exactly once per claim and the hot/cold race disappears along with the
         * machinery that exists to police it. See
         * {@link io.outbox.dispatch.OutboxDispatcher.Builder#hotPathEnabled(boolean)}.
         *
         * @param hotPathEnabled whether the hot path is in use
         * @return this builder
         */
        public SingleNodeBuilder hotPathEnabled(boolean hotPathEnabled) {
            this.hotPathEnabled = hotPathEnabled;
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
            if (!hotPathEnabled) {
                // Poller-only delivery rests on the claim being exclusive, and single-node takes no
                // claims — it polls without locking. Nothing would then stop a poll from handing
                // out a row whose listener is still running, so the mode cannot keep the promise
                // its name makes. The dispatcher's in-flight guard narrows that to a race rather
                // than closing it, and a guarantee is not something to leave to a race.
                throw new IllegalStateException(
                        "hotPathEnabled(false) requires claim locking, which singleNode() does not "
                                + "use: without a claim the poller re-delivers any row whose "
                                + "listener outlives the poll interval. Use multiNode() with "
                                + "claimLocking() — it is correct on one node too.");
            }
            return buildComposite(
                    workerCount, hotQueueCapacity, coldQueueCapacity,
                    maxAttempts, retryPolicy,
                    null, null, hotPathEnabled);
        }
    }

    // ── Multi-node builder ───────────────────────────────────────────

    /**
     * Builder for multi-node deployments: hot path + claim-based poller locking.
     */
    public static final class MultiNodeBuilder extends AbstractBuilder<MultiNodeBuilder> {
        private int workerCount = 4;
        private int hotQueueCapacity = 1000;
        private boolean hotPathEnabled = true;
        private int coldQueueCapacity = 1000;
        private int maxAttempts = 10;
        private RetryPolicy retryPolicy;
        private String ownerId;
        private Duration lockTimeout;

        MultiNodeBuilder() {
        }

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
         * Whether after-commit events are dispatched from memory. On by default.
         *
         * <p>Off makes the poller the only delivery path: no writer hook is installed, so an event
         * is dispatched exactly once per claim and the hot/cold race disappears along with the
         * machinery that exists to police it. See
         * {@link io.outbox.dispatch.OutboxDispatcher.Builder#hotPathEnabled(boolean)}.
         *
         * @param hotPathEnabled whether the hot path is in use
         * @return this builder
         */
        public MultiNodeBuilder hotPathEnabled(boolean hotPathEnabled) {
            this.hotPathEnabled = hotPathEnabled;
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
         * @param ownerId     unique identifier for this instance
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
            if (!hotPathEnabled && !outboxStore.supportsClaimLocking()) {
                // claimLocking() configures an owner; it does not make the store honour one. The
                // SPI's claimPending falls through to an unlocked read by default, so without this
                // a custom store would pass every check here and still re-deliver anything slower
                // than the poll interval — the defect singleNode() is refused for, unannounced.
                throw new IllegalStateException(
                        "hotPathEnabled(false) requires a store whose claimPending actually locks, "
                                + "and " + outboxStore.getClass().getName() + " does not report "
                                + "supportsClaimLocking(). Override it, or leave the hot path on.");
            }
            return buildComposite(
                    workerCount, hotQueueCapacity, coldQueueCapacity,
                    maxAttempts, retryPolicy,
                    ownerId, lockTimeout, hotPathEnabled);
        }
    }

    // ── Ordered builder ──────────────────────────────────────────────

    /**
     * Builder for ordered delivery: poller-only, single worker, no retry.
     *
     * <p>Forces {@code workerCount=1}, {@code maxAttempts=1}, and no {@link WriterHook}
     * (events are delivered exclusively via the poller).
     */
    // Builder fields intentionally share their setter names — that is the builder idiom,
    // and PMD's AvoidFieldNameMatchingMethodName has no exception for it. Suppressed at the
    // class so the rule stops reporting the whole builder every time one field is touched.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public static final class OrderedBuilder extends AbstractBuilder<OrderedBuilder> {

        OrderedBuilder() {
        }

        @Override
        public Outbox build() {
            validateRequired();
            return buildComposite(1, 1000, 1000, 1, null, null, null, false);
        }
    }

    // ── Writer-only builder ────────────────────────────────────────

    /**
     * Builder for writer-only (CDC) mode: no dispatcher or poller.
     *
     * <p>Creates only an {@link OutboxWriter} with an optional
     * {@link OutboxPurgeScheduler} for age-based cleanup. Intended for CDC
     * scenarios where events are consumed externally (e.g. Debezium).
     *
     * <p>Inherited builder methods for dispatcher/poller configuration
     * ({@code listenerRegistry}, {@code interceptor}, {@code interceptors},
     * {@code intervalMs}, {@code batchSize},
     * {@code skipRecent}, {@code drainTimeoutMs}) are not supported in this
     * mode and throw {@link UnsupportedOperationException}.
     */
    // Builder fields intentionally share their setter names — that is the builder idiom,
    // and PMD's AvoidFieldNameMatchingMethodName has no exception for it. Suppressed at the
    // class so the rule stops reporting the whole builder every time one field is touched.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
    public static final class WriterOnlyBuilder extends AbstractBuilder<WriterOnlyBuilder> {

        // Purge configuration used to live here and return WriterOnlyBuilder; it moved up to
        // AbstractBuilder so the dispatcher modes could reach it, which erases the return type to
        // AbstractBuilder. outbox is published as a library, so anything
        // already compiled against the old signatures would fail with NoSuchMethodError rather
        // than at compile time. These covariant overrides put the original signatures back.

        @Override
        public WriterOnlyBuilder purger(EventPurger purger) {
            return super.purger(purger);
        }

        @Override
        public WriterOnlyBuilder purgeRetention(Duration purgeRetention) {
            return super.purgeRetention(purgeRetention);
        }

        @Override
        public WriterOnlyBuilder purgeBatchSize(int purgeBatchSize) {
            return super.purgeBatchSize(purgeBatchSize);
        }

        @Override
        public WriterOnlyBuilder purgeIntervalSeconds(long purgeIntervalSeconds) {
            return super.purgeIntervalSeconds(purgeIntervalSeconds);
        }

        WriterOnlyBuilder() {
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder listenerRegistry(ListenerRegistry listenerRegistry) {
            throw new UnsupportedOperationException("listenerRegistry is not used in writer-only mode");
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder interceptor(EventInterceptor interceptor) {
            throw new UnsupportedOperationException("interceptor is not used in writer-only mode");
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder interceptors(List<EventInterceptor> interceptors) {
            throw new UnsupportedOperationException("interceptors is not used in writer-only mode");
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder intervalMs(long intervalMs) {
            throw new UnsupportedOperationException("intervalMs is not used in writer-only mode");
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder batchSize(int batchSize) {
            throw new UnsupportedOperationException("batchSize is not used in writer-only mode");
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder skipRecent(Duration skipRecent) {
            throw new UnsupportedOperationException("skipRecent is not used in writer-only mode");
        }

        /**
         * @throws UnsupportedOperationException always — not used in writer-only mode
         */
        @Override
        public WriterOnlyBuilder drainTimeoutMs(long drainTimeoutMs) {
            throw new UnsupportedOperationException("drainTimeoutMs is not used in writer-only mode");
        }





        @Override
        void validateRequired() {
            Objects.requireNonNull(txContext, "txContext");
            Objects.requireNonNull(outboxStore, "outboxStore");
            if (purger != null) {
                Objects.requireNonNull(connectionProvider, "connectionProvider");
            }
        }

        @Override
        public Outbox build() {
            validateRequired();
            markBuilt();
            OutboxWriter writer = new DefaultOutboxWriter(txContext, outboxStore);
            return new Outbox(writer, null, null, buildPurgeScheduler(), metrics);
        }
    }
}
