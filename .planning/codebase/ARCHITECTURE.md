# Architecture

**Analysis Date:** 2026-02-19

## Pattern Overview

**Overall:** Dual-queue event broker with at-least-once delivery semantics.

**Key Characteristics:**

- Hot-path (afterCommit callbacks) bypasses database for sub-millisecond delivery
- Cold-path (scheduled poller) provides fallback recovery when hot queue full or unavailable
- Single listener per (aggregateType, eventType) pair; unroutable events marked DEAD immediately
- Fair round-robin queue draining (2:1 hot:cold ratio) prevents cold path starvation
- Graceful shutdown with configurable drain timeout for in-flight events
- JDBC-based persistence with database-specific claim locking for multi-node deployments

## Layers

**API & Composition:**

- Purpose: High-level entry points and scenario-specific builders
- Location: `outbox-core/src/main/java/outbox/Outbox.java`, `outbox/OutboxWriter.java`
- Contains: Four sealed builder types (SingleNodeBuilder, MultiNodeBuilder, OrderedBuilder, WriterOnlyBuilder) via
  CRTP (Curiously Recurring Template Pattern)
- Depends on: Dispatcher, Poller, Writer, Registry, Store, TxContext
- Used by: Application code to bootstrap the framework

**Hot Path (Synchronous Dispatch):**

- Purpose: Real-time event delivery after transaction commit
- Location: `outbox-core/src/main/java/outbox/dispatch/` (OutboxDispatcher, DispatcherWriterHook, QueuedEvent)
- Contains: Dual-queue processor with configurable worker threads, retry policy, in-flight deduplication, metrics
  integration
- Depends on: OutboxStore (for status updates), ListenerRegistry, EventInterceptor, TxContext (via connection)
- Used by: WriterHook lifecycle → dispatcher enqueues individually as QueuedEvent(event, HOT, 0)

**Cold Path (Scheduled Recovery):**

- Purpose: Fallback polling when hot queue full, unavailable, or events missed
- Location: `outbox-core/src/main/java/outbox/poller/OutboxPoller.java`
- Contains: Scheduled executor that polls database periodically; supports single-node (pollPending) and multi-node (
  claimPending with row-level locks)
- Depends on: OutboxStore, ConnectionProvider, EventInterceptor, TxContext
- Used by: DispatcherPollerHandler enqueues to cold queue

**Event Model & Lifecycle:**

- Purpose: Immutable event representation and status tracking
- Location: `outbox-core/src/main/java/outbox/EventEnvelope.java`, `outbox/model/OutboxEvent.java`,
  `outbox/model/EventStatus.java`
- Contains: EventEnvelope builder with ULID generation, validation (max 1MB payload, non-empty eventType), EventStatus
  enum (NEW, RETRY, DONE, DEAD)
- Depends on: ULID library (f4b6a3)
- Used by: Writer, Dispatcher, Poller, Listener registry

**Registry & Routing:**

- Purpose: Map events to listeners by (aggregateType, eventType)
- Location: `outbox-core/src/main/java/outbox/registry/ListenerRegistry.java`,
  `outbox/registry/DefaultListenerRegistry.java`
- Contains: Single listener per (agg, type) pair; AggregateType.GLOBAL ("__GLOBAL__") is default fallback
- Depends on: EventListener interface
- Used by: Dispatcher to find handler; unregistered events immediately marked DEAD

**JDBC Persistence:**

- Purpose: Database-agnostic outbox storage with SQL generation per database
- Location: `outbox-jdbc/src/main/java/outbox/jdbc/store/AbstractJdbcOutboxStore.java`, subclasses (H2, MySQL,
  PostgreSQL)
- Contains: Shared insert/select/update logic; each subclass overrides claimPending() for row-level locking strategy
- Depends on: JdbcTemplate (lightweight query helper), TableNames (validation), JsonCodec
- Used by: Dispatcher, Poller, DeadEventManager

**Transaction Management:**

- Purpose: Abstract transaction lifecycle without Spring dependency
- Location: `outbox-jdbc/src/main/java/outbox/jdbc/tx/ThreadLocalTxContext.java`
- Contains: ThreadLocal connection binding, afterCommit/afterRollback callback queuing, exception aggregation
- Depends on: None (Spring TxContext available in outbox-spring-adapter)
- Used by: OutboxWriter to bracket event writes and trigger hooks

**Dead Event Management:**

- Purpose: Query, count, and replay failed events
- Location: `outbox-core/src/main/java/outbox/dead/DeadEventManager.java`
- Contains: Connection-managed facade for inspecting DEAD status events
- Depends on: OutboxStore SPI (queryDead, countDead, replayDead)
- Used by: Operations/admin tools

**Purge Scheduler:**

- Purpose: Automatic cleanup of terminal events (DONE + DEAD) or age-based cleanup
- Location: `outbox-core/src/main/java/outbox/purge/OutboxPurgeScheduler.java`,
  `outbox-jdbc/src/main/java/outbox/jdbc/purge/` (status-based + age-based hierarchies)
- Contains: Scheduled executor that loops batch deletions until count < batchSize; two hierarchies (
  AbstractJdbcEventPurger for terminal, AbstractJdbcAgeBasedPurger for age-based)
- Depends on: EventPurger SPI, ConnectionProvider
- Used by: Outbox builders to enable optional periodic cleanup

**Metrics & Observability:**

- Purpose: Export dispatch and queue metrics to monitoring systems
- Location: `outbox-micrometer/src/main/java/outbox/micrometer/MicrometerMetricsExporter.java`,
  `outbox-core/src/main/java/outbox/spi/MetricsExporter.java` (SPI)
- Contains: Counters and gauges for hot/cold enqueued, dropped, dispatched, retried, marked dead; optional namePrefix
  for multi-instance
- Depends on: Micrometer (optional)
- Used by: Dispatcher, Poller, DispatcherWriterHook

## Data Flow

**Hot-Path Event Dispatch:**

1. Application code calls `writer.write(event)` or `writer.writeAll(events)` inside transaction
2. OutboxWriter runs `WriterHook.beforeWrite()` → may transform/suppress
3. Events inserted via OutboxStore.insertBatch()
4. OutboxWriter runs `WriterHook.afterWrite()` (observational)
5. Transaction commits successfully
6. TxContext fires `WriterHook.afterCommit()` with committed events
7. DispatcherWriterHook enqueues each event individually as QueuedEvent(event, HOT, 0)
8. Dispatcher worker thread acquires event from hot queue
9. DefaultInFlightTracker checks eventId → returns early if duplicate in-flight
10. EventInterceptor.beforeDispatch() runs (audit, logging, etc.)
11. ListenerRegistry finds listener for (aggregateType, eventType)
12. Listener.onEvent(envelope) executes synchronously
13. EventInterceptor.afterDispatch() runs
14. OutboxStore.markDone(eventId) or markRetry/markDead on exception
15. Event removed from in-flight tracking

**Cold-Path Fallback:**

1. Hot queue full → DispatcherWriterHook logs WARNING, event stays in database
2. OutboxPoller scheduled task polls periodically
3. Poller queries OutboxStore.pollPending() (single-node) or claimPending() (multi-node)
4. Rows locked/claimed to prevent duplicate processing across nodes
5. DispatcherPollerHandler forwards event to cold queue as QueuedEvent(event, COLD, attempts)
6. Dispatcher fair-drains cold queue (1/3 ratio when pollCounter % 3 == 2)
7. Same dispatch logic as hot path (interceptors → listener → markDone/markRetry/markDead)

**State Management:**

- **In-Memory:** DefaultInFlightTracker holds eventId → expiry map; periodic eviction every ~1000 acquires
- **Database:** EventStatus tracks lifecycle (NEW → RETRY/DONE/DEAD); timestamp fields track attempts and lock expiry
- **Queue State:** BlockingQueue[QueuedEvent] holds Source (HOT/COLD) and attempt count for retry policy decisions

## Key Abstractions

**OutboxStore SPI:**

- Purpose: Persistence contract for insert, select, update, dead-event-ops
- Examples: `outbox-jdbc/src/main/java/outbox/jdbc/store/AbstractJdbcOutboxStore.java` (base), H2OutboxStore,
  MySqlOutboxStore, PostgresOutboxStore
- Pattern: Template method (shared SQL in base, subclasses override database-specific claim strategies like FOR UPDATE
  SKIP LOCKED)

**TxContext SPI:**

- Purpose: Transaction lifecycle abstraction (isTransactionActive, currentConnection, afterCommit, afterRollback)
- Examples: `outbox-jdbc/src/main/java/outbox/jdbc/tx/ThreadLocalTxContext.java` (manual JDBC),
  `outbox-spring-adapter/src/main/java/outbox/spring/SpringTxContext.java` (Spring)
- Pattern: Adapter over transaction manager; callbacks queued and invoked on commit/rollback

**EventInterceptor:**

- Purpose: Cross-cutting before/after dispatch hooks for audit, metrics, logging
- Examples: Registered via Outbox.builder().interceptor() or interceptors()
- Pattern: Chain of responsibility; beforeDispatch in order, afterDispatch in reverse

**WriterHook:**

- Purpose: Batch write lifecycle (beforeWrite → insert → afterWrite → commit → afterCommit/afterRollback)
- Examples: WriterHook.NOOP (default), DispatcherWriterHook (hot-path bridge)
- Pattern: Strategy; allows both filtering/enrichment (beforeWrite) and side effects (afterCommit)

**RetryPolicy:**

- Purpose: Compute delay between retry attempts
- Examples: ExponentialBackoffRetryPolicy (default, 200ms→60s with jitter [0.5, 1.5))
- Pattern: Strategy; pluggable via Outbox.builder().retryPolicy()

**InFlightTracker:**

- Purpose: Prevent duplicate dispatch of same eventId
- Examples: DefaultInFlightTracker (expiring map, periodic cleanup)
- Pattern: Strategy; pluggable via OutboxDispatcher.Builder.inFlightTracker()

**JsonCodec:**

- Purpose: Encode/decode event headers (Map[String,String] ↔ JSON)
- Examples: DefaultJsonCodec (zero-dependency, built-in), injectable for Jackson/Gson
- Pattern: Strategy; injectable into OutboxStore, OutboxPoller, JdbcOutboxStores.detect()

## Entry Points

**Outbox (Composite Builder):**

- Location: `outbox-core/src/main/java/outbox/Outbox.java`
- Triggers: Application startup in try-with-resources
- Responsibilities: Wires writer + optional dispatcher/poller/purgeScheduler into single AutoCloseable; provides four
  sealed builder types for topology flexibility

**SingleNodeBuilder:**

- Location: `outbox-core/src/main/java/outbox/Outbox.java` (inner class)
- Triggers: `Outbox.singleNode()` factory
- Responsibilities: Hot path + poller fallback; configurable worker count, queue capacities, retry policy, max attempts

**MultiNodeBuilder:**

- Location: `outbox-core/src/main/java/outbox/Outbox.java` (inner class)
- Triggers: `Outbox.multiNode()` factory
- Responsibilities: Hot path + claim-based poller; requires claimLocking(ownerId, lockTimeout)

**OrderedBuilder:**

- Location: `outbox-core/src/main/java/outbox/Outbox.java` (inner class)
- Triggers: `Outbox.ordered()` factory
- Responsibilities: Poller-only, single worker, no retry; forces workerCount=1, maxAttempts=1, no WriterHook

**WriterOnlyBuilder:**

- Location: `outbox-core/src/main/java/outbox/Outbox.java` (inner class)
- Triggers: `Outbox.writerOnly()` factory
- Responsibilities: CDC mode (writer only, no dispatcher/poller); optional age-based purge scheduler

**OutboxWriter.write/writeAll:**

- Location: `outbox-core/src/main/java/outbox/OutboxWriter.java`
- Triggers: Application code inside transaction
- Responsibilities: Batch write with WriterHook lifecycle (beforeWrite → insert → afterWrite →
  afterCommit/afterRollback); returns event IDs or null if suppressed

**OutboxDispatcher.enqueueHot/enqueueCold:**

- Location: `outbox-core/src/main/java/outbox/dispatch/OutboxDispatcher.java`
- Triggers: DispatcherWriterHook.afterCommit (hot) or DispatcherPollerHandler (cold)
- Responsibilities: Offer event to bounded queue; return false if full or shutdown; record metrics

**OutboxPoller.start:**

- Location: `outbox-core/src/main/java/outbox/poller/OutboxPoller.java`
- Triggers: Outbox.buildComposite() or manual start()
- Responsibilities: Begin scheduled polling loop; first poll delayed by intervalMs; loops until close()

## Error Handling

**Strategy:** Defensive null checks, exception aggregation on shutdown, callback resilience (exceptions
logged/swallowed).

**Patterns:**

- **Listener Exceptions:** Caught by dispatcher worker → marked RETRY (exponential backoff) → after maxAttempts marked
  DEAD → logged
- **WriterHook Exceptions:** beforeWrite throws → aborts write (transaction rolls back);
  afterWrite/afterCommit/afterRollback throw → logged/swallowed
- **Callback Aggregation:** ThreadLocalTxContext collects exceptions from multiple afterCommit/afterRollback callbacks;
  throws single exception with suppressed chain
- **Shutdown Exceptions:** Outbox.close() catches each component exception, aggregates with suppressed(), rethrows first
- **Queue Offer Failures:** DispatcherWriterHook logs WARNING, increments metrics.hotDropped()

## Cross-Cutting Concerns

**Logging:** Standard `java.util.logging`; worker threads log dispatch failures, queue drops, lifecycle events at
WARNING/INFO levels.

**Validation:** EventEnvelope enforces non-empty eventType, max 1MB payload, no null header keys; TableNames validates
outbox table name pattern.

**Authentication:** Not a concern; TxContext abstracts transaction management (delegates to Spring/manual JDBC).

---

*Architecture analysis: 2026-02-19*
