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
8. [JDBC Outbox Store](#8-jdbc-outbox-store)
9. [OutboxDispatcher](#9-outboxdispatcher)
10. [OutboxPoller](#10-outboxpoller)
11. [Registries](#11-registries)
12. [Retry Policy](#12-retry-policy)
13. [Backpressure and Downgrade](#13-backpressure-and-downgrade)
14. [Configuration](#14-configuration)
15. [Observability](#15-observability)
16. [Thread Safety](#16-thread-safety)
17. [Event Purge](#17-event-purge)
18. [Dead Event Management](#18-dead-event-management)
19. [Ordered Delivery](#19-ordered-delivery)
20. [Outbox Composite Builder](#20-outbox-composite-builder)

---

## 1. Architecture Overview

### 1.1 Components

| Component | Responsibility |
|-----------|----------------|
| **Outbox** | Composite builder (`singleNode`/`multiNode`/`ordered`) that wires dispatcher, poller, and writer into a single `AutoCloseable` |
| **OutboxWriter** | API used by business code inside a transaction context |
| **TxContext** | Abstraction for transaction lifecycle hooks (afterCommit/afterRollback) |
| **OutboxStore** | Insert/update/query via `java.sql.Connection` |
| **OutboxDispatcher** | Hot/cold queues + worker pool; executes listeners; updates status |
| **ListenerRegistry** | Maps event types to event listeners |
| **OutboxPoller** | Low-frequency fallback DB scan; forwards events to handler |
| **OutboxPurgeScheduler** | Scheduled purge of terminal events (DONE/DEAD) older than retention |
| **InFlightTracker** | In-memory deduplication |

### 1.2 Event Flow

```text
+--------------------------------------------------------------+
|                     Transaction Scope                         |
|                                                               |
|  Business Code --> OutboxWriter.write()                       |
|                         |                                     |
|                         +--> OutboxStore.insertNew() --> [DB] |
|                         |                                     |
|                         +--> TxContext.afterCommit(hook)       |
+-------------------------+-------------------------+-----------+
                          |                         |
                   afterCommit hook             poll pending
                          |                         |
                          v                         v
                     HOT PATH                  COLD PATH
                          |                         |
                          v                         v
         WriterHook.afterCommit()       OutboxPoller.poll()
         Dispatcher.enqueueHot()         pollPending()/claimPending()
                          |              Handler.handle()
                          |              Dispatcher.enqueueCold()
                          |                         |
                          +-----------+-------------+
                                      |
                                      v
                         OutboxDispatcher.process()
                           -> ListenerRegistry.listenerFor()
                           -> EventListener.onEvent()
                           -> markDone/Retry/Dead()
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
- `outbox` - Main API: Outbox (composite builder), OutboxWriter, EventEnvelope, EventType, AggregateType, EventListener, WriterHook
- `outbox.spi` - Extension point interfaces: TxContext, ConnectionProvider, OutboxStore, EventPurger, MetricsExporter
- `outbox.model` - Domain objects: OutboxEvent, EventStatus
- `outbox.dispatch` - OutboxDispatcher, retry policy, inflight tracking
- `outbox.poller` - OutboxPoller, OutboxPollerHandler
- `outbox.registry` - Listener registry
- `outbox.purge` - OutboxPurgeScheduler (scheduled purge of terminal events)
- `outbox.dead` - DeadEventManager (connection-managed facade for dead event queries)
- `outbox.util` - JsonCodec (interface), DefaultJsonCodec (built-in zero-dependency implementation)

### 2.2 outbox-jdbc

JDBC outbox store hierarchy, event purger hierarchy, and manual transaction helpers.

Packages:
- `outbox.jdbc` — Shared utilities: JdbcTemplate, OutboxStoreException, DataSourceConnectionProvider
- `outbox.jdbc.store` — OutboxStore hierarchy (ServiceLoader-registered)
- `outbox.jdbc.purge` — EventPurger hierarchy
- `outbox.jdbc.tx` — Transaction management

Classes by package:

**`outbox.jdbc.store`**
- `AbstractJdbcOutboxStore` - Base outbox store with shared SQL, row mapper, and H2-compatible default claim
- `H2OutboxStore` - H2 (inherits default subquery-based claim)
- `MySqlOutboxStore` - MySQL/TiDB (UPDATE...ORDER BY...LIMIT claim)
- `PostgresOutboxStore` - PostgreSQL (FOR UPDATE SKIP LOCKED + RETURNING claim)
- `JdbcOutboxStores` - ServiceLoader registry with `detect(DataSource)` auto-detection

**`outbox.jdbc.purge`**
- `AbstractJdbcEventPurger` - Base event purger with subquery-based DELETE (H2/PostgreSQL default)
- `H2EventPurger` - H2 (inherits default)
- `MySqlEventPurger` - MySQL/TiDB (DELETE...ORDER BY...LIMIT)
- `PostgresEventPurger` - PostgreSQL (inherits default)

**`outbox.jdbc.tx`**
- `ThreadLocalTxContext` - ThreadLocal-based TxContext for manual transactions
- `JdbcTransactionManager` - Helper for manual JDBC transactions

**`outbox.jdbc`** (root)
- `JdbcTemplate` - Lightweight JDBC helper (update, query, updateReturning)
- `OutboxStoreException` - JDBC-layer exception
- `DataSourceConnectionProvider` - ConnectionProvider from DataSource

### 2.3 outbox-spring-adapter

Optional Spring integration.

Classes:
- `SpringTxContext` - Implements TxContext using Spring's TransactionSynchronizationManager

### 2.4 outbox-micrometer

Micrometer metrics bridge for Prometheus, Grafana, Datadog, and other monitoring backends.

Classes:
- `MicrometerMetricsExporter` - Implements `MetricsExporter` using Micrometer `MeterRegistry`

### 2.5 samples/outbox-demo

Standalone H2 demonstration (no Spring).

### 2.6 samples/outbox-spring-demo

Spring Boot REST API demonstration.

### 2.7 samples/outbox-multi-ds-demo

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
- `afterCommit()`/`afterRollback()` registration requires transaction synchronization to be active.
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

### 3.4 JsonCodec

```java
public interface JsonCodec {
  static JsonCodec getDefault() { ... }

  String toJson(Map<String, String> headers);
  Map<String, String> parseObject(String json);
}
```

- `getDefault()` returns the singleton `DefaultJsonCodec` — a lightweight, zero-dependency encoder/decoder that only supports flat `Map<String, String>` objects.
- `toJson()` returns `null` for null or empty maps; rejects null keys with `IllegalArgumentException`.
- `parseObject()` returns an empty map for `null`, empty, or `"null"` input.
- Users who already have Jackson or Gson on the classpath can implement this interface and inject it into:
  - `AbstractJdbcOutboxStore` constructor: `new H2OutboxStore(tableName, codec)`
  - `OutboxPoller.Builder.jsonCodec(codec)`
  - `JdbcOutboxStores.detect(dataSource, codec)`

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
- Header map MUST NOT contain null keys.

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
  public OutboxWriter(TxContext txContext, OutboxStore outboxStore);
  public OutboxWriter(TxContext txContext, OutboxStore outboxStore, WriterHook writerHook);

  public String write(EventEnvelope event);              // returns null if suppressed
  public String write(String eventType, String payloadJson);  // returns null if suppressed
  public String write(EventType eventType, String payloadJson); // returns null if suppressed
  public List<String> writeAll(List<EventEnvelope> events);  // returns empty list if suppressed
}
```

Semantics:
- MUST require an active transaction via TxContext
- `write()` delegates to `writeAll()` (single-element list)
- `writeAll()` calls `WriterHook.beforeWrite()` which may transform or suppress the list
- If `beforeWrite` returns null or empty, no events are inserted (suppressed write)
- MUST insert outbox rows (NEW) using `TxContext.currentConnection()` within the current transaction
- MUST register a single `afterCommit`/`afterRollback` callback per `writeAll` batch
- If the hook throws in `afterWrite`/`afterCommit`/`afterRollback`, it MUST NOT propagate (log and continue)
- If no hook is provided (or `WriterHook.NOOP`), no post-commit action is executed (poller/CDC is responsible)

### 7.2 WriterHook

```java
public interface WriterHook {
  default List<EventEnvelope> beforeWrite(List<EventEnvelope> events) { return events; }
  default void afterWrite(List<EventEnvelope> events) {}
  default void afterCommit(List<EventEnvelope> events) {}
  default void afterRollback(List<EventEnvelope> events) {}

  WriterHook NOOP = new WriterHook() {};
}
```

Lifecycle: `beforeWrite` (transform/suppress) → insert → `afterWrite` → tx commit/rollback → `afterCommit`/`afterRollback`.

- `beforeWrite` may return a modified list; returning null or empty suppresses the write
- `afterWrite`/`afterCommit`/`afterRollback` exceptions are swallowed and logged
- `DispatcherWriterHook` implements `afterCommit` to enqueue each event into the dispatcher's hot queue

---

## 8. JDBC Outbox Store

### 8.1 Interface

```java
public interface OutboxStore {
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
    .outboxStore(outboxStore)                  // required
    .listenerRegistry(listenerRegistry)      // required
    .inFlightTracker(tracker)                // default: DefaultInFlightTracker
    .retryPolicy(policy)                     // default: ExponentialBackoffRetryPolicy(200, 60_000)
    .maxAttempts(10)                         // default: 10
    .workerCount(4)                          // default: 4
    .hotQueueCapacity(1000)                  // default: 1000
    .coldQueueCapacity(1000)                 // default: 1000
    .metrics(metricsExporter)                // default: MetricsExporter.NOOP
    .interceptor(interceptor)                // optional, repeatable
    .interceptors(List.of(i1, i2))           // optional, bulk add
    .drainTimeoutMs(5000)                    // default: 5000
    .build();
```

### 9.2 Methods

```java
boolean enqueueHot(QueuedEvent event)  // Returns false if queue full or shutting down
boolean enqueueCold(QueuedEvent event) // Returns false if queue full or shutting down
int coldQueueRemainingCapacity()       // Number of slots available in cold queue
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

### 10.1 Builder

```java
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)  // required
    .outboxStore(outboxStore)                  // required
    .handler(handler)                        // required
    .skipRecent(Duration.ofSeconds(1))       // default: Duration.ZERO
    .batchSize(50)                           // default: 50
    .intervalMs(5000)                        // default: 5000
    .metrics(metricsExporter)                // default: MetricsExporter.NOOP
    .claimLocking("poller-1", Duration.ofMinutes(5))  // optional: enables multi-node claim locking
    .build();
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
- Skips events created within `skipRecent` duration (default `Duration.ZERO`)
- Queries status IN (0, 2) with available_at <= now
- Converts OutboxEvent to EventEnvelope
- Delegates to handler for processing (subject to backpressure)
- On decode failure: marks event DEAD

### 10.4 Event Locking

When `claimLocking` is configured, the poller uses claim-based locking:

- **Claim**: Sets `locked_by` and `locked_at` on pending events atomically
- **Expiry**: Locks older than the configured timeout are considered expired and can be reclaimed
- **Release**: `markDone`/`markRetry`/`markDead` clear `locked_by` and `locked_at`
- **Database-specific**: PostgreSQL uses `FOR UPDATE SKIP LOCKED` + `RETURNING`; MySQL uses `UPDATE...ORDER BY...LIMIT`; H2 uses subquery-based two-phase claim

### 10.5 OutboxPollerHandler

```java
@FunctionalInterface
public interface OutboxPollerHandler {
  boolean handle(EventEnvelope event, int attempts);

  default int availableCapacity() {
    return Integer.MAX_VALUE;
  }
}
```

- Handler invoked for each decoded event
- Returning false stops the current poll cycle (backpressure)

### 10.6 CDC Alternative (Optional)

For high-QPS workloads, CDC can replace the in-process poller and hot-path hook:

- Construct `OutboxWriter` without a hook (or with `WriterHook.NOOP`)
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

### 13.4 Hot Queue Full Behavior (DispatcherWriterHook)

- `write()` MUST NOT throw
- `DispatcherWriterHook` logs WARNING and increments metric when the hot queue drops
- Event remains in DB with status NEW
- OutboxPoller or CDC picks up when workers have capacity

### 13.5 Cold Queue Full Behavior

- OutboxPoller stops enqueueing for current cycle
- Events remain in DB, retry on next poll cycle
- No data loss

---

## 14. Configuration

The recommended way to configure the outbox is via the `Outbox` composite builder (`Outbox.singleNode()`, `Outbox.multiNode()`, `Outbox.ordered()`, `Outbox.writerOnly()`), which wires all components with correct defaults. For advanced use cases, `OutboxDispatcher.Builder` and `OutboxPoller.Builder` are available directly.

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

### 14.2 Composite Builder Example

```java
try (Outbox outbox = Outbox.singleNode()
    .connectionProvider(connectionProvider)
    .txContext(txContext)
    .outboxStore(outboxStore)
    .listenerRegistry(registry)
    .workerCount(8)
    .hotQueueCapacity(2000)
    .build()) {
  OutboxWriter writer = outbox.writer();
  // use writer inside transactions...
}
```

### 14.3 Manual Builder Example

```java
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
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

### 15.2 MicrometerMetricsExporter

The `outbox-micrometer` module provides `MicrometerMetricsExporter`, a ready-to-use implementation that registers counters and gauges with a Micrometer `MeterRegistry`.

**Constructors:**

```java
new MicrometerMetricsExporter(MeterRegistry registry)                // default prefix: "outbox"
new MicrometerMetricsExporter(MeterRegistry registry, String namePrefix) // custom prefix
```

**Counters (monotonically increasing):**

| Metric Name | Description |
|-------------|-------------|
| `{prefix}.enqueue.hot` | Events enqueued via hot path |
| `{prefix}.enqueue.hot.dropped` | Events dropped (hot queue full) |
| `{prefix}.enqueue.cold` | Events enqueued via cold (poller) path |
| `{prefix}.dispatch.success` | Events dispatched successfully |
| `{prefix}.dispatch.failure` | Events failed (will retry) |
| `{prefix}.dispatch.dead` | Events moved to DEAD |

**Gauges (current value):**

| Metric Name | Description |
|-------------|-------------|
| `{prefix}.queue.hot.depth` | Current hot queue depth |
| `{prefix}.queue.cold.depth` | Current cold queue depth |
| `{prefix}.lag.oldest.ms` | Lag of oldest pending event in milliseconds |

The default prefix is `outbox`. For multi-instance setups, use a custom prefix (e.g., `"orders.outbox"`) to avoid metric collisions.

### 15.3 Logging

| Level | Event |
|-------|-------|
| WARNING | Hot queue drop (DispatcherWriterHook) |
| ERROR | DEAD transition |
| ERROR | OutboxDispatcher/poller loop errors |
| SEVERE | Decode failures (malformed headers) |

Hot queue drop warnings are emitted by `DispatcherWriterHook`. If no hook is installed (CDC-only), no warning or metric is produced.

### 15.4 Idempotency Requirements

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
| OutboxPurgeScheduler | Single-thread ScheduledExecutorService |
| ThreadLocalTxContext | ThreadLocal storage |

---

## 17. Event Purge

### 17.1 Overview

The outbox table is a transient buffer, not an outbox store. Terminal events (DONE and DEAD) should be purged after a retention period to prevent table bloat and maintain poller query performance. If clients need to archive events for audit, they should do so in their `EventListener`.

### 17.2 EventPurger Interface

```java
public interface EventPurger {
  int purge(Connection conn, Instant before, int limit);
}
```

- Deletes terminal events (DONE + DEAD) where `COALESCE(done_at, created_at) < before`
- Takes explicit `Connection` (caller controls transaction), matching the `OutboxStore` pattern
- Returns count of rows deleted
- `limit` caps the batch size per call to limit lock duration

### 17.3 JDBC Purger Hierarchy

| Class | Database | Strategy |
|-------|----------|----------|
| `AbstractJdbcEventPurger` | Base | Subquery-based DELETE (default) |
| `H2EventPurger` | H2 | Inherits default |
| `MySqlEventPurger` | MySQL/TiDB | `DELETE...ORDER BY...LIMIT` |
| `PostgresEventPurger` | PostgreSQL | Inherits default |

**Default purge SQL (H2, PostgreSQL):**
```sql
DELETE FROM outbox_event WHERE event_id IN (
  SELECT event_id FROM outbox_event
  WHERE status IN (1, 3) AND COALESCE(done_at, created_at) < ?
  ORDER BY created_at LIMIT ?
)
```

**MySQL purge SQL:**
```sql
DELETE FROM outbox_event
WHERE status IN (1, 3) AND COALESCE(done_at, created_at) < ?
ORDER BY created_at LIMIT ?
```

All purger classes support a custom table name via constructor (validated with the same regex as `AbstractJdbcOutboxStore`).

### 17.4 OutboxPurgeScheduler

Scheduled component modeled after `OutboxPoller`: builder pattern, `AutoCloseable`, daemon threads, synchronized lifecycle.

#### Builder

```java
OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)  // required
    .purger(purger)                          // required
    .retention(Duration.ofDays(7))           // default: 7 days
    .batchSize(500)                          // default: 500
    .intervalSeconds(3600)                   // default: 3600 (1 hour)
    .build();
```

| Parameter | Type | Default | Required |
|-----------|------|---------|----------|
| `connectionProvider` | `ConnectionProvider` | - | yes |
| `purger` | `EventPurger` | - | yes |
| `retention` | `Duration` | 7 days | no |
| `batchSize` | `int` | 500 | no |
| `intervalSeconds` | `long` | 3600 | no |

#### Methods

```java
void start()    // Start scheduled purge loop
void runOnce()  // Execute single purge cycle (loops batches until count < batchSize)
void close()    // Stop purge and shut down scheduler thread
```

#### Behavior

- Each purge cycle calculates cutoff as `Instant.now().minus(retention)`
- Loops batches: each batch gets its own auto-committed connection
- Stops when a batch deletes fewer than `batchSize` rows (backlog drained)
- Logs total purged count at INFO level
- Errors are caught and logged at SEVERE (does not propagate)
- Calling `start()` after `close()` MUST throw `IllegalStateException`.

---

## 18. Dead Event Management

### 18.1 Overview

Events that exceed `maxAttempts` or have no registered listener are marked DEAD. The framework provides tooling to query, count, and replay dead events without writing raw SQL.

### 18.2 OutboxStore SPI Methods

The `OutboxStore` interface includes default methods for dead event operations:

```java
default List<OutboxEvent> queryDead(Connection conn, String eventType, String aggregateType, int limit);
default int replayDead(Connection conn, String eventId);
default int countDead(Connection conn, String eventType);
```

- `queryDead` — returns dead events matching optional filters (`null` for all), ordered oldest first
- `replayDead` — resets a single DEAD event to NEW status (returns number of rows updated)
- `countDead` — counts dead events, optionally filtered by event type

### 18.3 DeadEventManager

`DeadEventManager` (`outbox.dead`) is a convenience facade that manages connection lifecycle internally using a `ConnectionProvider`:

```java
public final class DeadEventManager {
  public DeadEventManager(ConnectionProvider connectionProvider, OutboxStore outboxStore);

  public List<OutboxEvent> query(String eventType, String aggregateType, int limit);
  public boolean replay(String eventId);
  public int replayAll(String eventType, String aggregateType, int batchSize);
  public int count(String eventType);
}
```

**Methods:**

| Method | Description |
|--------|-------------|
| `query(eventType, aggregateType, limit)` | Query dead events with optional filters (`null` for all) |
| `replay(eventId)` | Replay a single dead event by resetting it to NEW; returns `true` if replayed |
| `replayAll(eventType, aggregateType, batchSize)` | Replay all matching dead events in batches; returns total replayed |
| `count(eventType)` | Count dead events, optionally filtered by event type (`null` for all) |

### 18.4 Error Handling

All `DeadEventManager` methods catch `SQLException` and log at `SEVERE` level:
- `query()` returns `List.of()` on failure
- `replay()` returns `false` on failure
- `replayAll()` returns the count replayed so far and stops on failure
- `count()` returns `0` on failure

---

## 19. Ordered Delivery

### 19.1 Poller-Only, Single Worker Mode

For per-aggregate FIFO ordering:

| Setting | Value | Reason |
|---------|-------|--------|
| `WriterHook` | `NOOP` (default) | Disable hot path to avoid dual-path reordering |
| `OutboxPoller` | Single node | Prevent cross-node claim interleaving |
| `workerCount` | `1` | Sequential dispatch preserves poll order |

The poller reads events in `ORDER BY created_at` order. The single dispatch
worker processes them sequentially, guaranteeing that events for the same
aggregate are delivered in insertion order.

### 19.2 Why Poller-Only

The dual hot+cold path architecture makes ordering hard in general — events
for the same aggregate can arrive via different paths in unpredictable order.
Disabling the hot path (`WriterHook.NOOP`) ensures all events flow through
the poller, which reads them in DB insertion order.

### 19.3 Retry Breaks Ordering

If event A fails and is marked RETRY with exponential backoff, its `available_at`
is set to a future timestamp. Meanwhile, event B (same aggregate, inserted after A)
has `available_at <= now` and will be polled and delivered before A's retry becomes
eligible — breaking per-aggregate ordering.

**Mitigation:** Set `maxAttempts(1)` so failed events go directly to DEAD without
retry. Use `DeadEventManager` to inspect and replay them manually after fixing the
underlying issue.

### 19.4 Limitations

- Higher latency than hot-path mode (bounded by poll interval).
- Throughput limited by single-worker sequential processing (sufficient when
  DB poll is the bottleneck).
- Ordering is per-node; no cross-node ordering guarantee.
- Retries break ordering (see 19.3); use `maxAttempts(1)` for strict FIFO.

---

## 20. Outbox Composite Builder

### 20.1 Overview

The `Outbox` class is the recommended entry point for wiring the framework. It provides four scenario-specific builders that create an `OutboxWriter` and optionally an `OutboxDispatcher`, `OutboxPoller`, and `OutboxPurgeScheduler` as a single `AutoCloseable` unit.

| Builder | Hot Path | Poller Mode | workerCount | maxAttempts | WriterHook |
|---------|----------|-------------|-------------|-------------|------------|
| `Outbox.singleNode()` | Yes | `pollPending` | user-set (default 4) | user-set (default 10) | `DispatcherWriterHook` |
| `Outbox.multiNode()` | Yes | `claimPending` | user-set (default 4) | user-set (default 10) | `DispatcherWriterHook` |
| `Outbox.ordered()` | No | `pollPending` | 1 (forced) | 1 (forced) | `NOOP` (forced) |
| `Outbox.writerOnly()` | No | None | N/A | N/A | `NOOP` (forced) |

### 20.2 Sealed Builder Hierarchy

```
Outbox (final, AutoCloseable)
├── singleNode()   → SingleNodeBuilder
├── multiNode()    → MultiNodeBuilder
├── ordered()      → OrderedBuilder
├── writerOnly()   → WriterOnlyBuilder
│
└── AbstractBuilder<B> (sealed, permits 4 concrete builders)
    Required: connectionProvider, txContext, outboxStore, listenerRegistry
    Optional: metrics, jsonCodec, interceptor(s), intervalMs, batchSize,
              skipRecent, drainTimeoutMs
```

`SingleNodeBuilder` and `MultiNodeBuilder` add: `workerCount`, `hotQueueCapacity`, `coldQueueCapacity`, `maxAttempts`, `retryPolicy`. `MultiNodeBuilder` additionally requires `claimLocking(Duration)` or `claimLocking(String, Duration)`.

`OrderedBuilder` exposes no additional parameters.

`WriterOnlyBuilder` only requires `txContext` and `outboxStore`. Optionally accepts `purger`, `purgeRetention`, `purgeBatchSize`, `purgeIntervalSeconds` for age-based cleanup; if `purger` is set, `connectionProvider` is also required. Inherited dispatcher/poller settings are ignored.

### 20.3 Build Lifecycle

Each `build()`:

1. Validates required fields (`NullPointerException` if missing).
2. `MultiNodeBuilder` checks `claimLocking()` was called (`IllegalStateException` if not).
3. Builds `OutboxDispatcher` (workers start immediately). Skipped for `WriterOnlyBuilder`.
4. Builds `OutboxPoller` — wrapped in try-catch: if fails, dispatcher is closed before rethrowing. Skipped for `WriterOnlyBuilder`.
5. Starts poller. Skipped for `WriterOnlyBuilder`.
6. Creates `OutboxWriter` (with `DispatcherWriterHook` for single/multi-node, `NOOP` for ordered and writer-only).
7. `WriterOnlyBuilder` optionally builds and starts `OutboxPurgeScheduler` if a purger is configured.
8. Returns `Outbox`.

### 20.4 Shutdown

`Outbox.close()` shuts down in order (null components are skipped):

1. `purgeScheduler.close()` — stop purge schedule.
2. `poller.close()` — stop feeding cold queue.
3. `dispatcher.close()` — drain remaining events within `drainTimeoutMs`.

### 20.5 API

```java
// Single-node (hot + poller)
try (Outbox outbox = Outbox.singleNode()
    .connectionProvider(cp).txContext(tx).outboxStore(store).listenerRegistry(reg)
    .workerCount(4)
    .build()) {
  OutboxWriter writer = outbox.writer();
}

// Multi-node (hot + poller + claim locking)
try (Outbox outbox = Outbox.multiNode()
    .connectionProvider(cp).txContext(tx).outboxStore(store).listenerRegistry(reg)
    .claimLocking(Duration.ofMinutes(5))
    .workerCount(8)
    .build()) {
  OutboxWriter writer = outbox.writer();
}

// Ordered delivery (poller-only, single worker, no retry)
try (Outbox outbox = Outbox.ordered()
    .connectionProvider(cp).txContext(tx).outboxStore(store).listenerRegistry(reg)
    .intervalMs(1000)
    .build()) {
  OutboxWriter writer = outbox.writer();
}

// Writer-only (CDC mode, no dispatcher/poller)
try (Outbox outbox = Outbox.writerOnly()
    .txContext(tx).outboxStore(store)
    .build()) {
  OutboxWriter writer = outbox.writer();
}

// Writer-only with age-based purge
try (Outbox outbox = Outbox.writerOnly()
    .txContext(tx).outboxStore(store)
    .connectionProvider(cp)
    .purger(new H2AgeBasedPurger())
    .purgeRetention(Duration.ofHours(24))
    .purgeIntervalSeconds(1800)
    .build()) {
  OutboxWriter writer = outbox.writer();
}
```
