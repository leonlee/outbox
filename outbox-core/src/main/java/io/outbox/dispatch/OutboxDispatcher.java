package io.outbox.dispatch;

import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.EventListener;
import io.outbox.RetryAfterException;
import io.outbox.UnrecoverableException;
import io.outbox.registry.ListenerRegistry;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import io.outbox.util.DaemonThreadFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private boolean closed; // guarded by this
    private final AtomicInteger pollCounter = new AtomicInteger(0);
    /**
     * Ids sitting in a queue, waiting for a worker.
     *
     * <p>Handed over to {@link #inFlightTracker} the moment a worker acquires one, so between them
     * the two cover the whole window from enqueue to completion. The tracker alone is not enough:
     * a queued copy has not acquired anything yet, so a poll arriving while a backlog drains would
     * re-queue every row still waiting. Measured on a 4-row backlog in ordered mode before this
     * existed: 1, 3, 6 and 9 deliveries of the four rows.
     */
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    private final ConnectionProvider connectionProvider;
    private final OutboxStore outboxStore;
    private final ListenerRegistry listenerRegistry;
    private final InFlightTracker inFlightTracker;
    private final RetryPolicy retryPolicy;
    private final int maxAttempts;
    private final MetricsExporter metrics;
    private final List<EventInterceptor> interceptors;
    private final long drainTimeoutMs;

    /** Head-of-line age at which the hot path stops accepting; 0 disables the breaker. */
    private final long hotTripNanos;
    /** Age at which it starts accepting again — half of {@link #hotTripNanos}, for hysteresis. */
    private final long hotRecoverNanos;
    private final AtomicBoolean hotTripped = new AtomicBoolean(false);

    /** Keep terminal events in the tracker so a late second copy is rejected. Needs a TTL. */
    private final boolean suppressReplays;
    /** When false the hot queue is never fed, so the workers do not look at it. */
    private final boolean hotPathEnabled;

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

        if (builder.hotTripMs < 0) {
            throw new IllegalArgumentException("hotTripMs must be >= 0");
        }
        this.hotTripNanos = TimeUnit.MILLISECONDS.toNanos(builder.hotTripMs);
        this.hotRecoverNanos = this.hotTripNanos / 2;

        this.suppressReplays = builder.suppressReplays;
        this.hotPathEnabled = builder.hotPathEnabled;
        if (this.suppressReplays && !this.inFlightTracker.hasTtl()) {
            // Without expiry, a terminal entry is never reclaimed and that event can never be
            // dispatched again — a far worse failure than the duplicate this is meant to prevent.
            throw new IllegalArgumentException(
                    "suppressReplays requires an InFlightTracker with a TTL; "
                            + "construct DefaultInFlightTracker(ttlMs) with ttlMs > 0");
        }

        int maxAttempts = builder.maxAttempts;
        int workerCount = builder.workerCount;
        int hotQueueCapacity = builder.hotQueueCapacity;
        int coldQueueCapacity = builder.coldQueueCapacity;

        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
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
            // workerCount=0: no workers started; events remain queued (testing only). A cached
            // pool because nothing is ever submitted to it, so it never holds a thread.
            logger.warning("workerCount=0: no dispatch workers started; events will not be processed");
            this.workers = Executors.newCachedThreadPool(new DaemonThreadFactory("outbox-dispatcher-"));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Offers an event to the hot queue. Returns {@code false} if the queue is full, the
     * dispatcher is no longer accepting events, or the hot-path breaker is open because the
     * queue head has waited past {@link Builder#hotTripMs(long)} — in every case the caller
     * falls back to the poller.
     *
     * @param event the queued event to enqueue
     * @return {@code true} if the event was accepted
     */
    public boolean enqueueHot(QueuedEvent event) {
        if (!accepting.get()) {
            return false;
        }
        if (!hotPathEnabled) {
            // Nothing installs a writer hook in this mode, so reaching here means a caller went
            // around it. Refusing keeps the "every event arrives via a claim" invariant that lets
            // poller-only mode skip writer stamping.
            return false;
        }
        if (hotPathTripped()) {
            metrics.incrementHotTripped();
            metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
            return false;
        }
        String eventId = event.envelope().eventId();
        // The cold side is guarded for the poller; this is the mirror of it. skip-recent-ms
        // defaults to 0, so a poll can read a freshly committed row before afterCommit has run,
        // queue it, and then the hot copy arrives for the same event. Reported as accepted
        // because it is — the event is already on its way — and the writer hook's alternative
        // reading of false is "fall back to the poller", which is exactly what already happened.
        //
        // The add IS the reservation. A contains() check first would not be atomic: the poller's
        // thread and this one can both pass it and both offer, and without replay suppression the
        // second copy redelivers as soon as the first settles. Set.add admits exactly one caller.
        if (!reserve(eventId)) {
            metrics.incrementDispatchSuppressed();
            metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
            return true;
        }
        // Only this call's own reservation — reserve() returned true, so the id is ours to undo.
        boolean enqueued = hotQueue.offer(event);
        if (!enqueued) {
            queued.remove(eventId);
        }
        metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
        return enqueued;
    }

    /**
     * Whether the hot path is currently refusing work because it is not delivering fast enough
     * to be worth using.
     *
     * <p>The hot path exists to beat the poller to an event. It only does that if the event
     * reaches a terminal state before the poller is allowed to claim the row, i.e. within
     * {@code poller.skip-recent}. Past that point the hot copy is not faster — it is a second
     * copy racing the poller's, which is where duplicate dispatch comes from. So the precondition
     * is made explicit instead of assumed: trip on the age of the queue HEAD (the oldest waiter,
     * so it leads rather than lags), recover at half that age so a queue hovering at the
     * threshold does not flap.
     *
     * <p>Refusing is not dropping. {@code enqueueHot} returning false is the pre-existing,
     * well-exercised path — {@code DispatcherWriterHook} logs and leaves the row for the poller,
     * exactly as it already does when the queue is full.
     */
    private boolean hotPathTripped() {
        if (hotTripNanos == 0) {
            return false;
        }
        QueuedEvent head = hotQueue.peek();
        long headAge = head == null ? 0 : System.nanoTime() - head.enqueuedAtNanos();
        if (hotTripped.get()) {
            if (headAge <= hotRecoverNanos) {
                hotTripped.set(false);
                return false;
            }
            return true;
        }
        if (headAge > hotTripNanos) {
            hotTripped.set(true);
            logger.log(Level.INFO, () -> "Hot path tripped: head-of-line age "
                    + TimeUnit.NANOSECONDS.toMillis(headAge) + "ms exceeds "
                    + TimeUnit.NANOSECONDS.toMillis(hotTripNanos) + "ms; routing via poller");
            return true;
        }
        return false;
    }

    /**
     * Offers an event to the cold queue. Returns {@code false} if the queue is full
     * or the dispatcher is no longer accepting events.
     *
     * @param event the queued event to enqueue
     * @return {@code true} if the event was accepted
     */
    public boolean enqueueCold(QueuedEvent event) {
        if (!accepting.get()) {
            return false;
        }
        String eventId = event.envelope().eventId();
        // A poll can only see a row that is still PENDING, and a row stays PENDING for as long
        // as its listener runs — so any listener slower than the poll interval gets re-fetched
        // and queued again. Without claim locking nothing in the database prevents that, and
        // waiting to catch it at dispatch is too late: by then the first copy has settled and
        // released, and the second is delivered against a row that is already DONE.
        //
        // Reported as accepted so the poller works through the rest of its batch rather than
        // reading this as back-pressure and stopping. The suppression counter says it was dropped
        // rather than queued. Depths are recorded too, so the gauges do not freeze while a wedged
        // listener sends every polled copy down this path.
        //
        // The add IS the reservation — atomic, where contains-then-add lets two concurrent
        // callers both through and, without replay suppression, both copies deliver.
        if (!reserve(eventId)) {
            metrics.incrementDispatchSuppressed();
            metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
            return true;
        }
        // Only this call's own reservation — reserve() returned true, so the id is ours to undo.
        boolean enqueued = coldQueue.offer(event);
        if (!enqueued) {
            queued.remove(eventId);
        }
        metrics.recordQueueDepths(hotQueue.size(), coldQueue.size());
        return enqueued;
    }

    /**
     * Reserves {@code eventId} for one queued copy, or returns {@code false} if a copy is already
     * queued or running.
     *
     * <p>Reserve first, then ask the tracker. A worker acquires the tracker before it drops the
     * queued marker, so once {@code add} succeeds any earlier copy has either finished or is
     * visibly running. Asking the tracker first leaves a gap: the read says "not running", the
     * earlier copy then hands off from the queue to the tracker, and {@code add} succeeds behind a
     * listener that is already running.
     */
    private boolean reserve(String eventId) {
        if (!queued.add(eventId)) {
            return false;
        }
        // Undo this call's reservation unless it is kept — in a finally, as at the worker handoff,
        // so a tracker that throws cannot leave the id reserved with nothing queued, and every later
        // copy reported accepted and dropped.
        boolean reserved = false;
        try {
            reserved = !inFlightTracker.isRunning(eventId);
            return reserved;
        } finally {
            if (!reserved) {
                queued.remove(eventId);
            }
        }
    }

    public int coldQueueRemainingCapacity() {
        return coldQueue.remainingCapacity();
    }

    private QueuedEvent pollFairly() throws InterruptedException {
        if (!hotPathEnabled) {
            // No round-robin to run: the hot queue can never fill, so weighting it in would only
            // add the wait this method exists to avoid, and blocking on cold is also the lowest
            // wake-up latency for the only queue that gets fed.
            return coldQueue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
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
        // Both queues are drained without blocking first. Blocking on the primary while the
        // secondary holds work costs QUEUE_POLL_TIMEOUT_MS of dead wait per cycle, and the weighting
        // guarantees that happens on 2 of every 3 cycles whenever the hot queue is idle — capping a
        // worker at ~28 events/s no matter how much cold work is waiting. Only the genuinely idle
        // path may block.
        QueuedEvent event = primary.poll();
        if (event == null) {
            event = secondary.poll();
        }
        if (event == null) {
            // Nothing anywhere: park on the primary so an idle worker does not spin. An event
            // landing on the secondary meanwhile waits out the timeout, which is what an idle
            // dispatcher did before this too.
            event = primary.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
                    // An empty poll says nothing about the other queue: the blocking park watches
                    // only the primary, which is the cold queue on one cycle in three. Leaving on
                    // !running here would abandon a non-empty hot queue during shutdown, so let the
                    // guard at the top of the loop — which also checks both queues — decide.
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

    /**
     * Runs one event, then decides whether the tracker entry should be dropped or left to expire.
     *
     * <p>Releasing on every outcome — what this used to do — makes the tracker a guard against
     * <em>concurrent</em> duplicates only. Routine hot/cold duplicates are <em>sequential</em>: the hot
     * copy finishes and releases, and the poller's copy of the same row runs afterwards against an
     * empty tracker. Keeping terminal entries turns the tracker into a short-lived record of
     * "already settled", which is what rejects that second copy.
     *
     * <p>Non-terminal outcomes must still release immediately. RETRY and DEFERRED exist precisely
     * to be dispatched again, and {@code retry.base-delay-ms} is 200ms — holding those entries for
     * the TTL would silently stretch every retry interval to the TTL.
     *
     * <p>This only works with a TTL configured on the tracker; with {@code ttlMs = 0} a terminal
     * entry would never be reclaimed. {@link #OutboxDispatcher(Builder)} rejects that combination.
     */
    private void dispatchEvent(QueuedEvent event) {
        String eventId = event.envelope().eventId();
        // Acquire while the queued marker still covers the id, then remove the marker — in a
        // finally, so a tracker that throws cannot leave the id blocking every future copy.
        //
        // Both orderings were tried and the other one is wrong: removing before acquiring opens a
        // gap in which nothing covers the id, and a poll landing there queues a second copy. That
        // copy is only suppressed at ITS acquire if the first is still running; if the first has
        // finished and released — no replay suppression is the ordered/single-node default — the
        // second acquires cleanly and the event delivers twice, in sequence. Acquire-first has no
        // gap: isRunning becomes true before the marker is dropped.
        long token;
        try {
            token = inFlightTracker.acquire(eventId);
        } finally {
            queued.remove(eventId);
        }
        if (token == InFlightTracker.NOT_ACQUIRED) {
            metrics.incrementDispatchSuppressed();
            abandonWithoutStranding(event, eventId);
            return;
        }
        boolean settled = false;
        try {
            long listenerStartNs = System.nanoTime();
            DispatchResult result = deliverEvent(event.envelope());
            long listenerDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - listenerStartNs);
            metrics.recordListenerDurationMs(listenerDurationMs);

            if (result instanceof DispatchResult.RetryAfter retryAfter) {
                Instant nextAt = Instant.now().plus(retryAfter.delay());
                markDeferred(eventId, nextAt);
                metrics.incrementDispatchDeferred();
            } else if (result instanceof DispatchResult.Dead dead) {
                String reason = dead.reason() != null ? dead.reason() : "Listener returned Dead";
                settled = markDead(eventId, reason);
                metrics.incrementDispatchDead();
            } else if (result instanceof DispatchResult.Done) {
                long latencyMs = Instant.now().toEpochMilli() - event.envelope().occurredAt().toEpochMilli();
                if (latencyMs >= 0) {
                    metrics.recordDispatchLatencyMs(latencyMs);
                }
                settled = markDone(eventId);
                metrics.incrementDispatchSuccess();
            }
        } catch (Exception e) {
            settled = handleFailure(event, e);
        } finally {
            // Both name the token. After a TTL reclamation this acquisition is no longer the live
            // one, and ending somebody else's would hand the event out a third time.
            if (settled && suppressReplays) {
                // Stays tracked, but as a completed marker rather than an in-flight one, so a later
                // suppression can tell the two apart.
                inFlightTracker.markSettled(eventId, token);
            } else {
                inFlightTracker.release(eventId, token);
            }
        }
    }

    /**
     * Hands back the poller's lease when a dispatch is abandoned before it runs.
     *
     * <p>Suppression assumes the tracker entry and the row agree: the event settled, so the row is
     * DONE and nobody needs it. A replay breaks that assumption — {@code replayDead} puts the row
     * back to NEW while the settled marker is still inside its TTL, the poller claims it, and this
     * dispatch is then suppressed. The row would be left PENDING under a live lease with nothing
     * working on it, and in multi-node mode unclaimable by anyone until the lock timeout expires.
     * An explicit operator replay silently doing nothing for five minutes is not acceptable.
     *
     * <p>So the lease is dropped instead. {@link OutboxStore#releaseClaim} only touches rows still
     * in a pending state, which makes this a no-op in the ordinary case (the row really is settled)
     * and a repair in the replay case: the next poll picks the event up again, by which time the
     * marker has usually expired.
     *
     * <p>Only meaningful with replay suppression on. Without it, a failed {@code tryAcquire} means
     * another worker holds the event right now and will settle it — taking its lease away would be
     * wrong.
     */
    private void abandonWithoutStranding(QueuedEvent event, String eventId) {
        if (!suppressReplays || event.source() != QueuedEvent.Source.COLD) {
            return;
        }
        // Name the marker now: the database call below takes time, and by the time it returns
        // this one may have expired and been replaced by a newer acquisition's marker.
        long marker = inFlightTracker.settledToken(eventId);
        if (marker == InFlightTracker.NOT_ACQUIRED) {
            // Still in flight on this JVM. The lease is what keeps every other node off the row
            // while that listener runs; dropping it here would invite exactly the cross-JVM
            // duplicate this class is trying to remove. Whoever holds it will settle it, and the
            // terminal write clears the lease.
            return;
        }
        QueuedEvent.ClaimLease lease = event.claimLease();
        if (lease == null) {
            return;
        }
        int[] freed = {0};
        withConnection("release claim", eventId,
                conn -> freed[0] = outboxStore.releaseClaim(
                        conn, eventId, lease.owner(), lease.claimedAt()));
        if (freed[0] > 0) {
            // releaseClaim only matches rows still awaiting delivery, so a hit proves the row came
            // back after this marker was written — a replay, or any other reset. The marker is
            // therefore stale: drop it, and the next poll delivers the event instead of suppressing
            // it again on every poll until the TTL runs out.
            //
            // A miss means the row really is settled and the marker is doing its job, so it stays.
            //
            // Only the marker seen above, though. The database call takes time; the marker can
            // expire during it and the freed row be claimed, run and even settled again. An id-keyed
            // release would end that newer acquisition mid-listener, and a plain "is it settled"
            // check would delete its new marker and let a late copy through.
            inFlightTracker.releaseSettled(eventId, marker);
        }
    }

    private DispatchResult deliverEvent(EventEnvelope envelope) throws Exception {
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
            DispatchResult result = Objects.requireNonNull(
                    listener.onEvent(envelope),
                    "EventListener.onEvent() must not return null");

            runAfterDispatch(envelope, null, completedBefore);
            return result;
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

    /** @return {@code true} if the event reached a terminal state (DEAD); false if it will retry. */
    private boolean handleFailure(QueuedEvent event, Exception failure) {
        String eventId = event.envelope().eventId();
        if (failure instanceof UnrecoverableException) {
            boolean dead = markDead(eventId, failure);
            metrics.incrementDispatchDead();
            logger.log(Level.SEVERE, "Unrecoverable event marked DEAD: " + eventId, failure);
            return dead;
        }

        int nextAttempt = event.attempts() + 1;
        if (nextAttempt >= maxAttempts) {
            boolean dead = markDead(eventId, failure);
            metrics.incrementDispatchDead();
            logger.log(Level.SEVERE, "Event moved to DEAD after max attempts: " + eventId, failure);
            return dead;
        } else if (failure instanceof RetryAfterException retryAfterEx) {
            Instant nextAt = Instant.now().plus(retryAfterEx.retryAfter());
            markRetry(eventId, nextAt, failure);
            metrics.incrementDispatchFailure();
        } else {
            long delayMs = retryPolicy.computeDelayMs(nextAttempt);
            Instant nextAt = Instant.now().plusMillis(delayMs);
            markRetry(eventId, nextAt, failure);
            metrics.incrementDispatchFailure();
        }
        return false;
    }

    /** @return {@code false} if the write failed; {@link #withConnection} logs and swallows. */
    private boolean markDone(String eventId) {
        return withConnection("mark DONE", eventId,
                conn -> outboxStore.markDone(conn, eventId));
    }

    private boolean markDeferred(String eventId, Instant nextAt) {
        return withConnection("mark DEFERRED", eventId,
                conn -> outboxStore.markDeferred(conn, eventId, nextAt));
    }

    private boolean markRetry(String eventId, Instant nextAt, Exception failure) {
        return withConnection("mark RETRY", eventId,
                conn -> outboxStore.markRetry(conn, eventId, nextAt, failure == null ? null : failure.getMessage()));
    }

    private boolean markDead(String eventId, Exception failure) {
        return withConnection("mark DEAD", eventId,
                conn -> outboxStore.markDead(conn, eventId, failure == null ? null : failure.getMessage()));
    }

    private boolean markDead(String eventId, String reason) {
        return withConnection("mark DEAD", eventId,
                conn -> outboxStore.markDead(conn, eventId, reason));
    }

    /**
     * Runs a status write, logging and swallowing any failure.
     *
     * @return {@code true} if it completed. Callers that suppress later replays MUST gate on this:
     *         a swallowed failure leaves the row PENDING in the database, so treating it as settled
     *         in memory would block redelivery until the tracker TTL expires.
     */
    private boolean withConnection(String action, String eventId, SqlAction op) {
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(true);
            op.execute(conn);
            return true;
        } catch (SQLException | RuntimeException e) {
            logger.log(Level.SEVERE, "Failed to " + action + " for eventId=" + eventId, e);
            return false;
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void execute(Connection conn) throws SQLException;
    }

    /**
     * Initiates graceful shutdown: stops accepting new events, drains remaining queued
     * events within the configured drain timeout, then shuts down worker threads.
     *
     * <p>Idempotent, and a call that overlaps one already in progress waits for it to finish
     * rather than returning early, so no caller proceeds to release resources the workers are
     * still using.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
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
        // Dropping the in-memory copies loses nothing: hot events were committed before they were
        // queued and cold events were read from the table. The poller delivers them again — on its
        // next cycle for unclaimed rows, and only once the lock timeout expires for rows this
        // instance had claimed in multi-node mode (their leases are not released here).
        int strandedHot = hotQueue.size();
        int strandedCold = coldQueue.size();
        if (strandedHot > 0 || strandedCold > 0) {
            logger.log(Level.INFO, "Shutdown complete with {0} hot and {1} cold events still queued; "
                    + "the poller will deliver them again (claimed rows after their lock timeout)",
                    new Object[]{strandedHot, strandedCold});
        }
        hotQueue.clear();
        coldQueue.clear();
    }

    /**
     * Builder for {@link OutboxDispatcher}.
     */
    // Builder fields intentionally share their setter names — that is the builder idiom,
    // and PMD's AvoidFieldNameMatchingMethodName has no exception for it. Suppressed at the
    // class so the rule stops reporting the whole builder every time one field is touched.
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
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
        private long hotTripMs = 0;
        private boolean suppressReplays = false;
        private boolean hotPathEnabled = true;

        private Builder() {
        }

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
         * <p>Optional. Defaults to {@code 10}. Must be &ge; 0; {@code 0} marks an event DEAD on its
         * first failure.
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
            Objects.requireNonNull(interceptor, "interceptor");
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
            Objects.requireNonNull(interceptors, "interceptors");
            for (int i = 0; i < interceptors.size(); i++) {
                if (interceptors.get(i) == null) {
                    throw new NullPointerException("interceptors[" + i + "] is null");
                }
            }
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
         * Head-of-line age above which {@link #enqueueHot} refuses new events and lets the poller
         * deliver them instead. {@code 0} (the default) disables the breaker and preserves the
         * previous behaviour.
         *
         * <p>Set this <b>below</b> {@code poller.skip-recent}, leaving room for the listener and
         * the terminal write: the hot copy is only worth having if the row reaches a terminal
         * state before the poller may claim it, and the poller's cutoff is measured from
         * {@code created_at}. Above that age a hot copy is not a head start, it is a second copy
         * racing the poller's.
         *
         * <p>Recovery is at half this value (hysteresis).
         *
         * @param hotTripMs age in milliseconds, or 0 to disable
         * @return this builder
         */
        public Builder hotTripMs(long hotTripMs) {
            this.hotTripMs = hotTripMs;
            return this;
        }

        /**
         * Whether an event that reached a terminal state (DONE / DEAD) keeps its
         * {@link InFlightTracker} entry until the tracker's TTL expires it, instead of releasing
         * immediately.
         *
         * <p>Off by default, which leaves the tracker as a guard against concurrent duplicates
         * only. On, it also rejects a <em>sequential</em> second copy — the hot copy settles, and
         * the poller's copy of the same row arrives afterwards to find the event already recorded
         * as settled. RETRY and DEFERRED always release regardless, since they exist to be
         * dispatched again.
         *
         * <p>Requires a tracker with a TTL; {@code build()} rejects the combination otherwise.
         * Size the TTL above the worst enqueue-to-terminal delay you are willing to dedupe across
         * — bounded by {@link #hotTripMs(long)} in practice — and remember the entry count is
         * roughly {@code event rate × TTL}.
         *
         * @param suppressReplays whether terminal entries linger until TTL
         * @return this builder
         */
        public Builder suppressReplays(boolean suppressReplays) {
            this.suppressReplays = suppressReplays;
            return this;
        }

        /**
         * Whether the hot queue is in use. On by default.
         *
         * <p>Off means every event is delivered through a poller claim and nothing else. The hot
         * queue is then permanently empty, so the workers stop weighting it in and
         * {@link OutboxDispatcher#enqueueHot} refuses outright — a caller reaching it without a
         * writer hook installed is a bug, not a fallback.
         *
         * <p>Turning it off trades the hot path's sub-poll-interval latency for a much simpler set
         * of invariants: with no in-memory copy racing the row, there is no hot/cold duplicate to
         * suppress, no writer-owner stamp needed to keep other nodes off the row, and no
         * skip-recent grace period protecting a delivery that is already under way.
         *
         * @param hotPathEnabled whether after-commit events may be dispatched from memory
         * @return this builder
         */
        public Builder hotPathEnabled(boolean hotPathEnabled) {
            this.hotPathEnabled = hotPathEnabled;
            return this;
        }

        /**
         * Builds and starts the dispatcher. Worker threads begin draining queues immediately.
         *
         * @return a new {@link OutboxDispatcher} instance
         * @throws NullPointerException     if {@code connectionProvider}, {@code outboxStore},
         *                                  or {@code listenerRegistry} is null
         * @throws IllegalArgumentException if {@code maxAttempts < 0}, {@code workerCount < 0},
         *                                  or any queue capacity is &le; 0
         */
        public OutboxDispatcher build() {
            return new OutboxDispatcher(this);
        }
    }

}
