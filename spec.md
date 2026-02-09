# Outbox Framework Specification

Minimal, Spring-free transactional outbox framework with JDBC persistence, hot-path enqueue, and poller fallback.

## Table of Contents

1. [Goals](#1-goals)
2. [Architecture Overview](#2-architecture-overview)
3. [Modules](#3-modules)
4. [Core Abstractions](#4-core-abstractions)
5. [Data Model](#5-data-model)
6. [Event Envelope](#6-event-envelope)
7. [Type-Safe Event and Aggregate Types](#7-type-safe-event-and-aggregate-types)
8. [Public API](#8-public-api)
9. [JDBC Event Store](#9-jdbc-event-store)
10. [OutboxDispatcher](#10-dispatcher)
11. [OutboxPoller](#11-poller)
12. [Registries](#12-registries)
13. [Retry Policy](#13-retry-policy)
14. [Backpressure and Downgrade](#14-backpressure-and-downgrade)
15. [Configuration](#15-configuration)
16. [Observability](#16-observability)
17. [Thread Safety](#17-thread-safety)
18. [Acceptance Tests](#18-acceptance-tests)

---

## 1. Goals

Build a framework that:

1. Persists a unified event record into an outbox table within the current business DB transaction.
2. After successful transaction commit, enqueues the event (payload in memory) into an in-process OutboxDispatcher (fast path).
3. OutboxDispatcher executes registered EventListeners (send to MQ, update caches, call APIs, etc.).
4. On success, OutboxDispatcher updates outbox status to DONE; on failure updates to RETRY/DEAD.
5. A low-frequency OutboxPoller scans DB as fallback only (node crash, enqueue downgrade, missed enqueue) and enqueues unfinished events.
6. Delivery semantics: **at-least-once**; duplicates are allowed and must be handled downstream by `eventId`.

### Constraints

- Core MUST NOT depend on Spring (no Spring TX, no JdbcTemplate).
- DB access MUST use standard JDBC.
- Spring integration is optional and implemented via an adapter module.

### Non-Goals

- Exactly-once end-to-end.
- Distributed transactions.

---

## 2. Architecture Overview

### 2.1 Components

| Component | Responsibility |
|-----------|----------------|
| **OutboxWriter** | API used by business code inside a transaction context |
| **TxContext** | Abstraction for transaction lifecycle hooks (afterCommit/afterRollback) |
| **EventStore** | Insert/update/query via `java.sql.Connection` |
| **OutboxDispatcher** | Hot/cold queues + worker pool; executes listeners; updates status |
| **ListenerRegistry** | Maps event types to event listeners |
| **OutboxPoller** | Low-frequency fallback DB scan; enqueues cold events |
| **InFlightTracker** | In-memory deduplication |

### 2.2 Event Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              HOT PATH (Fast)                                  │
├──────────────────────────────────────────────────────────────────────────────┤
│  Business TX    OutboxWriter     afterCommit    OutboxDispatcher    DB       │
│      │               │               │               │              │        │
│      │──write()───>│               │               │              │        │
│      │               │──insertNew()─────────────────────────────────>│       │
│      │               │──register()──>│               │              │        │
│      │──commit()─────────────────────│               │              │        │
│      │               │               │──enqueueHot()>│              │        │
│      │               │               │               │──markDone───>│        │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│                           COLD PATH (Fallback)                                │
├──────────────────────────────────────────────────────────────────────────────┤
│   OutboxPoller          DB           OutboxDispatcher                         │
│       │                 │                 │                                   │
│       │──pollPending───>│                 │                                   │
│       │<──rows──────────│                 │                                   │
│       │──enqueueCold─────────────────────>│                                   │
│       │                 │                 │──process()                        │
│       │                 │<──markDone──────│                                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Queue Priority

OutboxDispatcher MUST prioritize:
- **Hot Queue**: afterCommit enqueue from business thread (priority)
- **Cold Queue**: poller enqueue fallback

---

## 3. Modules

### 3.1 outbox-core

Core interfaces, dispatcher, poller, and registries. **Zero external dependencies.**

Packages:
- `outbox` - Main API: OutboxWriter, EventEnvelope, EventType, AggregateType, EventListener
- `outbox.spi` - Extension point interfaces: TxContext, ConnectionProvider, EventStore, MetricsExporter
- `outbox.model` - Domain objects: OutboxEvent, EventStatus
- `outbox.dispatch` - OutboxDispatcher, retry policy, inflight tracking
- `outbox.poller` - OutboxPoller
- `outbox.registry` - Listener registry
- `outbox.util` - JsonCodec (no external JSON library)

### 3.2 outbox-jdbc

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

### 3.3 outbox-spring-adapter

Optional Spring integration.

Classes:
- `SpringTxContext` - Implements TxContext using Spring's TransactionSynchronizationManager

### 3.4 samples/outbox-demo

Standalone H2 demonstration (no Spring).

### 3.5 samples/outbox-spring-demo

Spring Boot REST API demonstration.

### 3.6 samples/outbox-multi-ds-demo

Multi-datasource demo (two H2 databases).

---

## 4. Core Abstractions

### 4.1 TxContext

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

### 4.2 ConnectionProvider

```java
public interface ConnectionProvider {
  Connection getConnection() throws SQLException;
}
```

Used by OutboxDispatcher and OutboxPoller for short-lived connections outside the business transaction.

### 4.3 Implementations

| Implementation | Module | Description |
|----------------|--------|-------------|
| `ThreadLocalTxContext` | outbox-jdbc | Manual JDBC transaction management |
| `SpringTxContext` | outbox-spring-adapter | Spring @Transactional integration |

---

## 5. Data Model

### 5.1 Table: outbox_event

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

### 5.2 Status Values

| Value | Name | Description |
|-------|------|-------------|
| 0 | NEW | Freshly inserted, awaiting processing |
| 1 | DONE | Successfully processed |
| 2 | RETRY | Failed, scheduled for retry |
| 3 | DEAD | Exceeded max attempts |

---

## 6. Event Envelope

### 6.1 Fields

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

### 6.2 Constraints

- Maximum payload size: **1MB** (1,048,576 bytes)
- Payload MUST be serialized once and reused for DB insert and dispatch
- EventEnvelope is immutable (defensive copies for bytes and headers)

### 6.3 Builder Pattern

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

### 6.4 Multi-Tenancy Support

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

## 7. Type-Safe Event and Aggregate Types

### 7.1 EventType Interface

```java
public interface EventType {
  String name();
}
```

### 7.2 Enum Implementation

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

### 7.3 Dynamic Implementation

```java
EventType type = StringEventType.of("DynamicEvent");

EventEnvelope.builder(type)
    .payloadJson("{}")
    .build();
```

### 7.4 AggregateType Interface

```java
public interface AggregateType {
  AggregateType GLOBAL = ...; // name() returns "__GLOBAL__"

  String name();
}
```

`AggregateType.GLOBAL` is the default aggregate type used when none is explicitly set on an `EventEnvelope`.

### 7.5 Aggregate Type Usage

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

## 8. Public API

### 8.1 OutboxWriter

```java
public final class OutboxWriter {
  public OutboxWriter(TxContext txContext, EventStore eventStore, OutboxDispatcher dispatcher);
  public OutboxWriter(TxContext txContext, EventStore eventStore, OutboxDispatcher dispatcher, MetricsExporter metrics);

  public String write(EventEnvelope event);
  public String write(String eventType, String payloadJson);
  public String write(EventType eventType, String payloadJson);
  public List<String> writeAll(List<EventEnvelope> events);
}
```

Semantics:
- MUST require an active transaction via TxContext
- MUST insert outbox row (NEW) using `TxContext.currentConnection()` within the current transaction
- MUST register `TxContext.afterCommit(() -> dispatcher.enqueueHot(event))`
- If enqueueHot fails due to backpressure, MUST NOT throw; rely on OutboxPoller fallback

---

## 9. JDBC Event Store

### 9.1 Interface

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

### 9.2 SQL Semantics

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

### 9.3 Rules

- MUST use PreparedStatement with bound parameters
- MUST NOT close transaction-bound connection (caller manages lifecycle)
- Error messages truncated to 4000 characters

---

## 10. OutboxDispatcher

### 10.1 Builder

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

### 10.2 Methods

```java
boolean enqueueHot(QueuedEvent event)  // Returns false if queue full or shutting down
boolean enqueueCold(QueuedEvent event) // Returns false if queue full or shutting down
boolean hasColdQueueCapacity()         // Check if cold queue has space
void close()                           // Graceful shutdown with drain
```

### 10.3 Processing Flow

For each queued event:
1. **Dedupe**: If eventId already inflight, drop
2. **Interceptors**: Run `beforeDispatch` in registration order
3. **Route**: Find single listener via `listenerRegistry.listenerFor(aggregateType, eventType)`
4. **Unroutable**: If no listener, throw `UnroutableEventException` → mark DEAD immediately (no retry)
5. **Execute**: Run the listener
6. **After**: Run `afterDispatch` in reverse order (null error on success, exception on failure)
7. **Success**: Update DB to DONE; remove from inflight
8. **Failure**: Update RETRY with backoff, or DEAD after maxAttempts; remove from inflight

### 10.4 Synchronous Execution Model

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

### 10.5 EventInterceptor

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

### 10.6 Fair Queue Draining

Workers use 2:1 weighted round-robin: hot queue gets 2/3 of poll attempts, cold queue 1/3.
This prevents cold queue starvation under sustained hot load.

### 10.7 Graceful Shutdown

`close()` stops accepting new events, signals workers to drain remaining events,
and waits up to `drainTimeoutMs` before forcing shutdown.

This is intentional for **natural backpressure**:
- `workerCount` = maximum concurrent events being processed
- Slow listeners → workers stay busy → cannot poll more events
- Queues fill up → `enqueueHot()` returns false → graceful degradation
- No risk of overwhelming downstream systems (MQ, databases, APIs)

**Tuning:** Adjust `workerCount` to control max parallelism. Higher values increase throughput but may overwhelm downstream services.

### 10.8 Queue Element

```java
public class QueuedEvent {
  EventEnvelope envelope;
  Source source;        // HOT or COLD
  int attempts;
}
```

### 10.9 InFlightTracker

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

## 11. OutboxPoller

### 11.1 Constructor

```java
// 7-arg: no locking (single-instance mode)
public OutboxPoller(
    ConnectionProvider connectionProvider,
    EventStore eventStore,
    OutboxDispatcher dispatcher,
    Duration skipRecent,
    int batchSize,
    long intervalMs,
    MetricsExporter metrics
)

// 9-arg: claim-based locking (multi-instance mode)
public OutboxPoller(
    ConnectionProvider connectionProvider,
    EventStore eventStore,
    OutboxDispatcher dispatcher,
    Duration skipRecent,
    int batchSize,
    long intervalMs,
    MetricsExporter metrics,
    String ownerId,         // null to auto-generate
    Duration lockTimeout    // null defaults to 5 minutes
)
```

### 11.2 Methods

```java
void start()    // Start scheduled polling
void poll()     // Execute single poll cycle
void close()    // Stop polling
```

### 11.3 Behavior

- Runs on scheduled interval (default 5000ms)
- Checks cold queue capacity before polling; skips cycle if full
- Skips events created within `skipRecent` duration (default 1000ms)
- Queries status IN (0, 2) with available_at <= now
- Converts OutboxEvent to EventEnvelope
- Enqueues to cold queue (subject to backpressure)
- On decode failure: marks event DEAD

### 11.4 Event Locking

When `ownerId` is provided (9-arg constructor), the poller uses claim-based locking:

- **Claim**: Sets `locked_by` and `locked_at` on pending events atomically
- **Expiry**: Locks older than `lockTimeout` are considered expired and can be reclaimed
- **Release**: `markDone`/`markRetry`/`markDead` clear `locked_by` and `locked_at`
- **Database-specific**: PostgreSQL uses `FOR UPDATE SKIP LOCKED` + `RETURNING`; MySQL uses `UPDATE...ORDER BY...LIMIT`; H2 uses subquery-based two-phase claim

---

## 12. Registries

### 12.1 EventListener Interface

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

### 12.2 ListenerRegistry Interface

```java
public interface ListenerRegistry {
  EventListener listenerFor(String aggregateType, String eventType);
}
```

Returns the single listener for the given `(aggregateType, eventType)`, or `null` if none registered.

### 12.3 DefaultListenerRegistry

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

### 12.4 Routing Rules

1. Lookup listener via `aggregateType + ":" + eventType` key
2. If found, execute the single listener
3. If not found, throw `UnroutableEventException` → event marked DEAD immediately (no retry)
4. For cross-cutting behavior (audit/logging), use `EventInterceptor` on the dispatcher builder

---

## 13. Retry Policy

### 13.1 Interface

```java
public interface RetryPolicy {
  long computeDelayMs(int attempts);
}
```

### 13.2 ExponentialBackoffRetryPolicy

```java
public ExponentialBackoffRetryPolicy(long baseDelayMs, long maxDelayMs)
```

Formula:
```
delay = min(maxDelay, baseDelay * 2^(attempts-1)) * jitter
jitter = random(0.5, 1.5)
```

### 13.3 Default Values

| Parameter | Default |
|-----------|---------|
| baseDelayMs | 200 |
| maxDelayMs | 60000 |
| maxAttempts | 10 |

---

## 14. Backpressure and Downgrade

The framework implements backpressure at multiple levels to prevent overwhelming downstream systems.

### 14.1 Backpressure Model

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         BACKPRESSURE FLOW                                 │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  [Slow Listener]                                                          │
│          │                                                                │
│          ▼                                                                │
│  [Workers Blocked] ──► Only N events processed concurrently               │
│          │              (N = workerCount)                                 │
│          ▼                                                                │
│  [Queues Fill Up] ──► Bounded capacity prevents memory growth             │
│          │                                                                │
│          ▼                                                                │
│  [enqueueHot() returns false]                                             │
│          │                                                                │
│          ▼                                                                │
│  [Event stays in DB] ──► OutboxPoller picks up when capacity frees        │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### 14.2 Bounded Queues

- Hot and cold queues MUST be bounded (`ArrayBlockingQueue`)
- Unbounded queues are forbidden
- Default capacity: 1000 each

### 14.3 Synchronous Worker Execution

Workers execute listeners synchronously (blocking). This provides natural rate limiting:

| Scenario | Effect |
|----------|--------|
| Fast listeners | Workers quickly return to polling, high throughput |
| Slow listeners | Workers blocked, queues fill, automatic throttling |
| Downstream outage | All workers blocked, queues full, events safe in DB |

**Key insight:** The database acts as a durable buffer when in-memory queues are full.

### 14.4 Hot Queue Full Behavior

- `write()` MUST NOT throw
- MUST log WARNING and increment metric
- Event remains in DB with status NEW
- OutboxPoller picks up when workers have capacity

### 14.5 Cold Queue Full Behavior

- OutboxPoller stops enqueueing for current cycle
- Events remain in DB, retry on next poll cycle
- No data loss

---

## 15. Configuration

Configuration is embedded in `OutboxDispatcher.Builder` and `OutboxPoller` constructor parameters. There is no separate config object.

### 15.1 Dispatcher Defaults

| Parameter | Default |
|-----------|---------|
| workerCount | 4 |
| hotQueueCapacity | 1000 |
| coldQueueCapacity | 1000 |
| maxAttempts | 10 |
| retryPolicy | ExponentialBackoffRetryPolicy(200, 60_000) |
| drainTimeoutMs | 5000 |
| metrics | MetricsExporter.NOOP |

### 15.2 Builder Example

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

## 16. Observability

### 16.1 MetricsExporter Interface

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

### 16.2 Logging

| Level | Event |
|-------|-------|
| WARNING | Hot queue drop (downgrade to poller) |
| ERROR | DEAD transition |
| ERROR | OutboxDispatcher/poller loop errors |
| SEVERE | Decode failures (malformed headers) |

### 16.3 Idempotency Requirements

- Listeners that publish to MQ MUST include eventId in message header/body
- Downstream systems must dedupe by eventId
- Framework provides at-least-once delivery

---

## 17. Thread Safety

| Component | Strategy |
|-----------|----------|
| OutboxDispatcher | Worker pool (ExecutorService), bounded BlockingQueues |
| Registries | ConcurrentHashMap |
| InFlightTracker | ConcurrentHashMap with CAS operations |
| OutboxPoller | Single-thread ScheduledExecutorService |
| ThreadLocalTxContext | ThreadLocal storage |

---

## 18. Acceptance Tests

### 18.1 Atomicity

- Begin tx manually, write event, rollback
- **Expect**: outbox row not present

### 18.2 Commit + Fast Path

- Begin tx, write event, commit
- **Expect**: dispatcher receives HOT event; listener invoked; outbox status DONE

### 18.3 Queue Overflow Downgrade

- Hot queue capacity small, force drop
- **Expect**: write returns OK, outbox row NEW
- Start poller
- **Expect**: row processed to DONE

### 18.4 Retry/Dead

- Listener fails repeatedly
- **Expect**: attempts increments, RETRY status
- After maxAttempts
- **Expect**: DEAD status

### 18.5 Type-Safe EventType

- Register listener with enum EventType
- Publish event using same enum
- **Expect**: listener invoked

### 18.6 Type-Safe AggregateType

- Publish event with enum AggregateType
- **Expect**: aggregateType() returns enum name string

---

## Appendix A: Quick Start (Manual JDBC)

```java
DataSource dataSource = /* your DataSource */;

var eventStore = JdbcEventStores.detect(dataSource);
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
ThreadLocalTxContext txContext = new ThreadLocalTxContext();

OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .eventStore(eventStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("UserCreated", event -> {
          // publish to MQ, update cache, call API, etc.
        }))
    .interceptor(EventInterceptor.before(event ->
        log.info("Dispatching: {}", event.eventType())))
    .build();

OutboxPoller poller = new OutboxPoller(
    connectionProvider, eventStore, dispatcher,
    Duration.ofMillis(1000), 200, 5000,
    MetricsExporter.NOOP
);
poller.start();

JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);

OutboxWriter client = new OutboxWriter(txContext, eventStore, dispatcher);

try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
  client.write("UserCreated", "{\"id\":123}");
  tx.commit();
}
```

## Appendix B: Spring Integration

```java
SpringTxContext txContext = new SpringTxContext(dataSource);
// Use OutboxWriter with SpringTxContext inside @Transactional methods
```
