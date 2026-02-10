[English](SPEC.md) | [中文](SPEC.zh-CN.md)

# Outbox Framework Technical Specification

Formal API contracts, data model, behavioral rules, configuration, and observability for the outbox-java framework.

For project goals and acceptance criteria, see [OBJECTIVE.md](OBJECTIVE.md).
For tutorials and code examples, see [TUTORIAL.md](TUTORIAL.md).

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Modules](#2-modules)
3. [Core Abstractions](#3-core-abstractions)
4. [Data Model](#4-data-model)
5. [Event Envelope](#5-event-envelope)
6. [Type-Safe Event and Aggregate Types](#6-type-safe-event-and-aggregate-types)
7. [Public API](#7-public-api)
8. [JDBC Event Store](#8-jdbc-event-store)
9. [OutboxDispatcher](#9-outboxdispatcher)
10. [OutboxPoller](#10-outboxpoller)
11. [Registries](#11-registries)
12. [Retry Policy](#12-retry-policy)
13. [Backpressure and Downgrade](#13-backpressure-and-downgrade)
14. [Configuration](#14-configuration)
15. [Observability](#15-observability)
16. [Thread Safety](#16-thread-safety)

---

## 1. Architecture Overview

### 1.1 Components

| Component | Responsibility |
|-----------|----------------|
| **OutboxWriter** | API used by business code inside a transaction context |
| **TxContext** | Abstraction for transaction lifecycle hooks (afterCommit/afterRollback) |
| **EventStore** | Insert/update/query via `java.sql.Connection` |
| **OutboxDispatcher** | Hot/cold queues + worker pool; executes listeners; updates status |
| **ListenerRegistry** | Maps event types to event listeners |
| **OutboxPoller** | Low-frequency fallback DB scan; forwards events to handler |
| **InFlightTracker** | In-memory deduplication |

### 1.2 Event Flow

```
HOT PATH (Fast)
Business TX -> OutboxWriter.write()
  -> EventStore.insertNew()  (inside TX)
  -> TxContext.afterCommit(...) registers hook
Commit
  -> AfterCommitHook.onCommit(event)
  -> OutboxDispatcher.enqueueHot(...)
  -> OutboxDispatcher.process()
  -> EventStore.markDone/Retry/Dead()

COLD PATH (Fallback)
OutboxPoller.poll()
  -> EventStore.pollPending()/claimPending()
  -> OutboxPollerHandler.handle(event, attempts)
  -> OutboxDispatcher.enqueueCold(...)
  -> OutboxDispatcher.process()
  -> EventStore.markDone/Retry/Dead()
```

### 1.3 Queue Priority

OutboxDispatcher MUST prioritize:
- **Hot Queue**: afterCommit enqueue from business thread (priority)
- **Cold Queue**: poller/handler fallback

---

## 2. Modules

### 2.1 outbox-core

Core interfaces, hooks, dispatcher, poller, and registries. **Zero external dependencies.**

Packages:
- `outbox` - Main API: OutboxWriter, EventEnvelope, EventType, AggregateType, EventListener
- `outbox.spi` - Extension point interfaces: TxContext, ConnectionProvider, EventStore, MetricsExporter, AfterCommitHook, OutboxPollerHandler
- `outbox.model` - Domain objects: OutboxEvent, EventStatus
- `outbox.dispatch` - OutboxDispatcher, retry policy, inflight tracking
- `outbox.poller` - OutboxPoller
- `outbox.registry` - Listener registry
- `outbox.util` - JsonCodec (no external JSON library)

### 2.2 outbox-jdbc

JDBC event store hierarchy and manual transaction helpers.

Classes:
- `AbstractJdbcEventStore` - Base event store with shared SQL, row mapper, and H2-compatible default claim
- `H2EventStore` - H2 (inherits default subquery-based claim)
- `MySqlEventStore` - MySQL/TiDB (UPDATE...ORDER BY...LIMIT claim)
- `PostgresEventStore` - PostgreSQL (FOR UPDATE SKIP LOCKED + RETURNING claim)
- `JdbcEventStores` - ServiceLoader registry with `detect(DataSource)` auto-detection
- `ThreadLocalTxContext` - ThreadLocal-based TxContext for manual transactions
- `JdbcTransactionManager` - Helper for manual JDBC transactions
- `DataSourceConnectionProvider` - ConnectionProvider from DataSource

### 2.3 outbox-spring-adapter

Optional Spring integration.

Classes:
- `SpringTxContext` - Implements TxContext using Spring's TransactionSynchronizationManager

### 2.4 samples/outbox-demo

Standalone H2 demonstration (no Spring).

### 2.5 samples/outbox-spring-demo

Spring Boot REST API demonstration.

### 2.6 samples/outbox-multi-ds-demo

Multi-datasource demo (two H2 databases).

---

## 3. Core Abstractions

### 3.1 TxContext

```java
public interface TxContext {
  boolean isTransactionActive();
  Connection currentConnection();
  void afterCommit(Runnable callback);
  void afterRollback(Runnable callback);
}
```

Rules:
- `currentConnection()` MUST return the same connection used by business operations.
- `afterCommit()` callback MUST run only if the transaction commits successfully.
- Core MUST fail-fast if `write()` called when `isTransactionActive() == false`.

### 3.2 ConnectionProvider

```java
public interface ConnectionProvider {
  Connection getConnection() throws SQLException;
}
```

Used by OutboxDispatcher and OutboxPoller for short-lived connections outside the business transaction.

### 3.3 Implementations

| Implementation | Module | Description |
|----------------|--------|-------------|
| `ThreadLocalTxContext` | outbox-jdbc | Manual JDBC transaction management |
| `SpringTxContext` | outbox-spring-adapter | Spring @Transactional integration |

---

## 4. Data Model

### 4.1 Table: outbox_event

```sql
CREATE TABLE outbox_event (
  event_id VARCHAR(36) PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL,
  aggregate_type VARCHAR(64),
  aggregate_id VARCHAR(128),
  tenant_id VARCHAR(64),
  payload JSON NOT NULL,
  headers JSON,
  status TINYINT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  done_at DATETIME(6),
  last_error TEXT,
  locked_by VARCHAR(128),
  locked_at DATETIME(6)
);

CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at);
```

### 4.2 Status Values

| Value | Name | Description |
|-------|------|-------------|
| 0 | NEW | Freshly inserted, awaiting processing |
| 1 | DONE | Successfully processed |
| 2 | RETRY | Failed, scheduled for retry |
| 3 | DEAD | Exceeded max attempts |

---

## 5. Event Envelope

### 5.1 Fields

| Field | Type | Required | Default |
|-------|------|----------|---------|
| eventId | String | No | ULID (monotonic) |
| eventType | String | Yes | - |
| occurredAt | Instant | No | Instant.now() |
| aggregateType | String | No | AggregateType.GLOBAL.name() (`"__GLOBAL__"`) |
| aggregateId | String | No | null |
| tenantId | String | No | null |
| headers | Map<String,String> | No | empty map |
| payloadJson | String | Yes* | - |
| payloadBytes | byte[] | Yes* | - |

*Either payloadJson or payloadBytes must be set, not both.

### 5.2 Constraints

- Maximum payload size: **1MB** (1,048,576 bytes)
- Payload MUST be serialized once and reused for DB insert and dispatch
- EventEnvelope is immutable (defensive copies for bytes and headers)

### 5.3 Builder Pattern

```java
// With type-safe EventType
EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
    .aggregateType(Aggregates.USER)
    .aggregateId("123")
    .payloadJson("{\"name\":\"John\"}")
    .build();

// With string
EventEnvelope envelope = EventEnvelope.builder("UserCreated")
    .payloadJson("{}")
    .build();

// Shorthand
EventEnvelope envelope = EventEnvelope.ofJson("UserCreated", "{}");
```

### 5.4 Multi-Tenancy Support

The `tenantId` field provides **pass-through metadata** for multi-tenant applications:

```java
EventEnvelope envelope = EventEnvelope.builder("OrderCreated")
    .tenantId("tenant-123")
    .aggregateId("order-456")
    .payloadJson("{...}")
    .build();
```

**Framework behavior:**
- `tenantId` is stored in the `outbox_event` table
- `tenantId` is included when polling and dispatching events
- Listeners receive `tenantId` via `event.tenantId()`

**Framework does NOT provide:**
- Tenant-based filtering during polling
- Tenant isolation or partitioning
- Per-tenant configuration

**Application responsibility:**
- Set `tenantId` when publishing events
- Use `tenantId` in listeners to route events or apply tenant-specific logic
- Implement tenant isolation at the database level if required (e.g., row-level security, separate schemas)

---

## 6. Type-Safe Event and Aggregate Types

### 6.1 EventType Interface

```java
public interface EventType {
  String name();
}
```

### 6.2 Enum Implementation

```java
public enum UserEvents implements EventType {
  USER_CREATED,
  USER_UPDATED,
  USER_DELETED;
}

// Usage
EventEnvelope.builder(UserEvents.USER_CREATED)
    .payloadJson("{}")
    .build();
```

### 6.3 Dynamic Implementation

```java
EventType type = StringEventType.of("DynamicEvent");

EventEnvelope.builder(type)
    .payloadJson("{}")
    .build();
```

### 6.4 AggregateType Interface

```java
public interface AggregateType {
  AggregateType GLOBAL = ...; // name() returns "__GLOBAL__"

  String name();
}
```

`AggregateType.GLOBAL` is the default aggregate type used when none is explicitly set on an `EventEnvelope`.

### 6.5 Aggregate Type Usage

```java
// Enum-based
public enum Aggregates implements AggregateType {
  USER, ORDER, PRODUCT
}

EventEnvelope.builder(eventType)
    .aggregateType(Aggregates.USER)
    .aggregateId("user-123")
    .build();

// Dynamic
EventEnvelope.builder(eventType)
    .aggregateType(StringAggregateType.of("CustomAggregate"))
    .aggregateId("id-456")
    .build();
```

---

## 7. Public API

### 7.1 OutboxWriter

```java
public final class OutboxWriter {
  public OutboxWriter(TxContext txContext, EventStore eventStore);
  public OutboxWriter(TxContext txContext, EventStore eventStore, AfterCommitHook afterCommitHook);

  public String write(EventEnvelope event);
  public String write(String eventType, String payloadJson);
  public String write(EventType eventType, String payloadJson);
  public List<String> writeAll(List<EventEnvelope> events);
}
```

Semantics:
- MUST require an active transaction via TxContext
- MUST insert outbox row (NEW) using `TxContext.currentConnection()` within the current transaction
- MUST register `TxContext.afterCommit(() -> afterCommitHook.onCommit(event))` when a hook is provided
- If the hook throws, it MUST NOT propagate to the caller (log and continue)
- If no hook is provided, no post-commit action is executed (poller/CDC is responsible)

### 7.2 AfterCommitHook

```java
@FunctionalInterface
public interface AfterCommitHook {
  void onCommit(EventEnvelope event);

  AfterCommitHook NOOP = event -> {};
}
```

- Hook invoked after transaction commit (optional)
- Used to connect hot-path dispatchers or external notifiers

---

## 8. JDBC Event Store

### 8.1 Interface

```java
public interface EventStore {
  void insertNew(Connection conn, EventEnvelope event);
  int markDone(Connection conn, String eventId);
  int markRetry(Connection conn, String eventId, Instant nextAt, String error);
  int markDead(Connection conn, String eventId, String error);
  List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit);

  // Claim-based locking (default falls back to pollPending)
  default List<OutboxEvent> claimPending(
      Connection conn, String ownerId, Instant now,
      Instant lockExpiry, Duration skipRecent, int limit);
}
```

### 8.2 SQL Semantics

**Insert New:**
```sql
INSERT INTO outbox_event (event_id, event_type, aggregate_type, aggregate_id,
  tenant_id, payload, headers, status, attempts, available_at, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
```

**Mark Done (idempotent):**
```sql
UPDATE outbox_event
SET status = 1, done_at = ?, locked_by = NULL, locked_at = NULL
WHERE event_id = ? AND status <> 1
```

**Mark Retry:**
```sql
UPDATE outbox_event
SET status = 2, attempts = attempts + 1, available_at = ?, last_error = ?,
    locked_by = NULL, locked_at = NULL
WHERE event_id = ? AND status <> 1
```

**Mark Dead:**
```sql
UPDATE outbox_event
SET status = 3, last_error = ?, locked_by = NULL, locked_at = NULL
WHERE event_id = ? AND status <> 1
```

**Poll Pending:**
```sql
SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id,
       payload, headers, attempts, created_at
FROM outbox_event
WHERE status IN (0, 2)
  AND available_at <= ?
  AND created_at <= ?
ORDER BY created_at
LIMIT ?
```

### 8.3 Rules

- MUST use PreparedStatement with bound parameters
- MUST NOT close transaction-bound connection (caller manages lifecycle)
- Error messages truncated to 4000 characters

---

## 9. OutboxDispatcher

### 9.1 Builder

```java
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)  // required
    .eventStore(eventStore)                  // required
    .listenerRegistry(listenerRegistry)      // required
    .inFlightTracker(tracker)                // default: DefaultInFlightTracker
    .retryPolicy(policy)                     // default: ExponentialBackoffRetryPolicy(200, 60_000)
    .maxAttempts(10)                         // default: 10
    .workerCount(4)                          // default: 4
    .hotQueueCapacity(1000)                  // default: 1000
    .coldQueueCapacity(1000)                 // default: 1000
    .metrics(metricsExporter)                // default: MetricsExporter.NOOP
    .interceptor(interceptor)                // optional, repeatable
    .drainTimeoutMs(5000)                    // default: 5000
    .build();
```

### 9.2 Methods

```java
boolean enqueueHot(QueuedEvent event)  // Returns false if queue full or shutting down
boolean enqueueCold(QueuedEvent event) // Returns false if queue full or shutting down
boolean hasColdQueueCapacity()         // Check if cold queue has space
void close()                           // Graceful shutdown with drain
```

### 9.3 Processing Flow

For each queued event:
1. **Dedupe**: If eventId already inflight, drop
2. **Interceptors**: Run `beforeDispatch` in registration order
3. **Route**: Find single listener via `listenerRegistry.listenerFor(aggregateType, eventType)`
4. **Unroutable**: If no listener, throw `UnroutableEventException` -> mark DEAD immediately (no retry)
5. **Execute**: Run the listener
6. **After**: Run `afterDispatch` in reverse order (null error on success, exception on failure)
7. **Success**: Update DB to DONE; remove from inflight
8. **Failure**: Update RETRY with backoff, or DEAD after maxAttempts; remove from inflight

### 9.4 Synchronous Execution Model

Workers execute listeners **synchronously** on the worker thread:

```
Worker Thread:
  loop:
    event = pollFairly()      // 2:1 hot:cold weighted round-robin
    interceptors.beforeDispatch(event)
    listener = registry.listenerFor(aggregateType, eventType)
    listener.onEvent(event)   // blocking
    interceptors.afterDispatch(event, null)
    markDone(event)
```

### 9.5 EventInterceptor

Cross-cutting hooks for audit, logging, and metrics:

```java
public interface EventInterceptor {
  default void beforeDispatch(EventEnvelope event) throws Exception {}
  default void afterDispatch(EventEnvelope event, Exception error) {}
}
```

- `beforeDispatch` runs in registration order; exception short-circuits to RETRY/DEAD
- `afterDispatch` runs in reverse order; exceptions are logged but swallowed
- Factory methods: `EventInterceptor.before(hook)`, `EventInterceptor.after(hook)`

### 9.6 Fair Queue Draining

Workers use 2:1 weighted round-robin: hot queue gets 2/3 of poll attempts, cold queue 1/3.
This prevents cold queue starvation under sustained hot load.

### 9.7 Graceful Shutdown

`close()` stops accepting new events, signals workers to drain remaining events,
and waits up to `drainTimeoutMs` before forcing shutdown.

This is intentional for **natural backpressure**:
- `workerCount` = maximum concurrent events being processed
- Slow listeners -> workers stay busy -> cannot poll more events
- Queues fill up -> `enqueueHot()` returns false -> graceful degradation
- No risk of overwhelming downstream systems (MQ, databases, APIs)

**Tuning:** Adjust `workerCount` to control max parallelism. Higher values increase throughput but may overwhelm downstream services.

### 9.8 Queue Element

```java
public class QueuedEvent {
  EventEnvelope envelope;
  Source source;        // HOT or COLD
  int attempts;
}
```

### 9.9 InFlightTracker

Prevents concurrent processing of the same event.

```java
public interface InFlightTracker {
  boolean tryAcquire(String eventId);  // Returns false if already in-flight
  void release(String eventId);         // Remove from tracking
}
```

**DefaultInFlightTracker**:
```java
new DefaultInFlightTracker()           // No TTL
new DefaultInFlightTracker(long ttlMs) // With TTL for stale entry recovery
```

- Uses ConcurrentHashMap for thread-safe tracking
- TTL allows recovery from stuck entries (e.g., worker crash)

---

## 10. OutboxPoller

### 10.1 Constructor

```java
// 7-arg: no locking (single-instance mode)
public OutboxPoller(
    ConnectionProvider connectionProvider,
    EventStore eventStore,
    OutboxPollerHandler handler,
    Duration skipRecent,
    int batchSize,
    long intervalMs,
    MetricsExporter metrics
)

// 9-arg: claim-based locking (multi-instance mode)
public OutboxPoller(
    ConnectionProvider connectionProvider,
    EventStore eventStore,
    OutboxPollerHandler handler,
    Duration skipRecent,
    int batchSize,
    long intervalMs,
    MetricsExporter metrics,
    String ownerId,         // null to auto-generate
    Duration lockTimeout    // null defaults to 5 minutes
)
```

### 10.2 Methods

```java
void start()    // Start scheduled polling
void poll()     // Execute single poll cycle
void close()    // Stop polling
```

### 10.3 Behavior

- Runs on scheduled interval (default 5000ms)
- Checks handler capacity before polling; skips cycle if full
- Skips events created within `skipRecent` duration (default 1000ms)
- Queries status IN (0, 2) with available_at <= now
- Converts OutboxEvent to EventEnvelope
- Delegates to handler for processing (subject to backpressure)
- On decode failure: marks event DEAD

### 10.4 Event Locking

When `ownerId` is provided (9-arg constructor), the poller uses claim-based locking:

- **Claim**: Sets `locked_by` and `locked_at` on pending events atomically
- **Expiry**: Locks older than `lockTimeout` are considered expired and can be reclaimed
- **Release**: `markDone`/`markRetry`/`markDead` clear `locked_by` and `locked_at`
- **Database-specific**: PostgreSQL uses `FOR UPDATE SKIP LOCKED` + `RETURNING`; MySQL uses `UPDATE...ORDER BY...LIMIT`; H2 uses subquery-based two-phase claim

### 10.5 OutboxPollerHandler

```java
@FunctionalInterface
public interface OutboxPollerHandler {
  boolean handle(EventEnvelope event, int attempts);

  default boolean hasCapacity() {
    return true;
  }
}
```

- Handler invoked for each decoded event
- Returning false stops the current poll cycle (backpressure)

### 10.6 CDC Alternative (Optional)

For high-QPS workloads, CDC can replace the in-process poller and hot-path hook:

- Construct `OutboxWriter` without a hook (or with `AfterCommitHook.NOOP`)
- Do not start `OutboxPoller`
- CDC consumer publishes downstream; status updates are optional in CDC-only mode
- If you do not mark DONE, treat the table as append-only and apply retention (e.g., partitioning + TTL)
- Dedupe by `event_id`

---

## 11. Registries

### 11.1 EventListener Interface

```java
/**
 * Listener that reacts to outbox events.
 *
 * Each (aggregateType, eventType) pair maps to exactly one listener.
 * For cross-cutting concerns (audit, logging), use EventInterceptor.
 */
public interface EventListener {
  void onEvent(EventEnvelope event) throws Exception;
}
```

### 11.2 ListenerRegistry Interface

```java
public interface ListenerRegistry {
  EventListener listenerFor(String aggregateType, String eventType);
}
```

Returns the single listener for the given `(aggregateType, eventType)`, or `null` if none registered.

### 11.3 DefaultListenerRegistry

```java
// Register with GLOBAL aggregate type (convenience)
registry.register("UserCreated", event -> { ... });

// Register with specific aggregate type
registry.register("Order", "OrderPlaced", event -> { ... });

// Type-safe registration
registry.register(Aggregates.USER, UserEvents.USER_CREATED, event -> { ... });
```

- Duplicate registration for the same `(aggregateType, eventType)` throws `IllegalStateException`
- Convenience `register(eventType, listener)` uses `AggregateType.GLOBAL`

### 11.4 Routing Rules

1. Lookup listener via `aggregateType + ":" + eventType` key
2. If found, execute the single listener
3. If not found, throw `UnroutableEventException` -> event marked DEAD immediately (no retry)
4. For cross-cutting behavior (audit/logging), use `EventInterceptor` on the dispatcher builder

---

## 12. Retry Policy

### 12.1 Interface

```java
public interface RetryPolicy {
  long computeDelayMs(int attempts);
}
```

### 12.2 ExponentialBackoffRetryPolicy

```java
public ExponentialBackoffRetryPolicy(long baseDelayMs, long maxDelayMs)
```

Formula:
```
delay = min(maxDelay, baseDelay * 2^(attempts-1)) * jitter
jitter = random(0.5, 1.5)
```

### 12.3 Default Values

| Parameter | Default |
|-----------|---------|
| baseDelayMs | 200 |
| maxDelayMs | 60000 |
| maxAttempts | 10 |

---

## 13. Backpressure and Downgrade

The framework implements backpressure at multiple levels to prevent overwhelming downstream systems.

### 13.1 Backpressure Model

```
+---------------------------------------------------------------------------+
|                         BACKPRESSURE FLOW                                  |
+---------------------------------------------------------------------------+
|                                                                            |
|  [Slow Listener]                                                           |
|          |                                                                 |
|          v                                                                 |
|  [Workers Blocked] --> Only N events processed concurrently                |
|          |              (N = workerCount)                                   |
|          v                                                                 |
|  [Queues Fill Up] --> Bounded capacity prevents memory growth              |
|          |                                                                 |
|          v                                                                 |
|  [enqueueHot() returns false]                                              |
|          |                                                                 |
|          v                                                                 |
|  [Event stays in DB] --> OutboxPoller/CDC picks up when capacity frees     |
|                                                                            |
+---------------------------------------------------------------------------+
```

### 13.2 Bounded Queues

- Hot and cold queues MUST be bounded (`ArrayBlockingQueue`)
- Unbounded queues are forbidden
- Default capacity: 1000 each

### 13.3 Synchronous Worker Execution

Workers execute listeners synchronously (blocking). This provides natural rate limiting:

| Scenario | Effect |
|----------|--------|
| Fast listeners | Workers quickly return to polling, high throughput |
| Slow listeners | Workers blocked, queues fill, automatic throttling |
| Downstream outage | All workers blocked, queues full, events safe in DB |

**Key insight:** The database acts as a durable buffer when in-memory queues are full.

### 13.4 Hot Queue Full Behavior (DispatcherCommitHook)

- `write()` MUST NOT throw
- `DispatcherCommitHook` logs WARNING and increments metric when the hot queue drops
- Event remains in DB with status NEW
- OutboxPoller or CDC picks up when workers have capacity

### 13.5 Cold Queue Full Behavior

- OutboxPoller stops enqueueing for current cycle
- Events remain in DB, retry on next poll cycle
- No data loss

---

## 14. Configuration

Configuration is embedded in `OutboxDispatcher.Builder` and `OutboxPoller` constructor parameters. There is no separate config object.

### 14.1 Dispatcher Defaults

| Parameter | Default |
|-----------|---------|
| workerCount | 4 |
| hotQueueCapacity | 1000 |
| coldQueueCapacity | 1000 |
| maxAttempts | 10 |
| retryPolicy | ExponentialBackoffRetryPolicy(200, 60_000) |
| drainTimeoutMs | 5000 |
| metrics | MetricsExporter.NOOP |

### 14.2 Builder Example

```java
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .eventStore(eventStore)
    .listenerRegistry(registry)
    .workerCount(8)
    .hotQueueCapacity(2000)
    .build();
```

---

## 15. Observability

### 15.1 MetricsExporter Interface

```java
public interface MetricsExporter {
  void incrementHotEnqueued();
  void incrementHotDropped();
  void incrementColdEnqueued();
  void incrementDispatchSuccess();
  void incrementDispatchFailure();
  void incrementDispatchDead();
  void recordQueueDepths(int hotDepth, int coldDepth);
  void recordOldestLagMs(long lagMs);

  MetricsExporter NOOP = new Noop();
}
```

### 15.2 Logging

| Level | Event |
|-------|-------|
| WARNING | Hot queue drop (DispatcherCommitHook) |
| ERROR | DEAD transition |
| ERROR | OutboxDispatcher/poller loop errors |
| SEVERE | Decode failures (malformed headers) |

Hot queue drop warnings are emitted by `DispatcherCommitHook`. If no hook is installed (CDC-only), no warning or metric is produced.

### 15.3 Idempotency Requirements

- Listeners that publish to MQ MUST include eventId in message header/body
- Downstream systems must dedupe by eventId
- Framework provides at-least-once delivery

---

## 16. Thread Safety

| Component | Strategy |
|-----------|----------|
| OutboxDispatcher | Worker pool (ExecutorService), bounded BlockingQueues |
| Registries | ConcurrentHashMap |
| InFlightTracker | ConcurrentHashMap with CAS operations |
| OutboxPoller | Single-thread ScheduledExecutorService |
| ThreadLocalTxContext | ThreadLocal storage |
