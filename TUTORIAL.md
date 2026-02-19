[English](TUTORIAL.md) | [中文](TUTORIAL.zh-CN.md)

# Outbox Framework Tutorial

Step-by-step guides and runnable code examples for the outbox-java framework.

For project goals, see [OBJECTIVE.md](OBJECTIVE.md).
For the full technical specification, see [SPEC.md](SPEC.md).

## Table of Contents

**Getting Started**

1. [Outbox Table Schemas](#1-outbox-table-schemas)
2. [Quick Start (Manual JDBC)](#2-quick-start-manual-jdbc)
3. [Full End-to-End Example (H2 In-Memory)](#3-full-end-to-end-example-h2-in-memory)

**Core Features**

4. [Type-Safe Event + Aggregate Types](#4-type-safe-event--aggregate-types)
5. [Spring Integration](#5-spring-integration)

**Advanced Topics**

6. [Poller Event Locking](#6-poller-event-locking)
7. [CDC Consumption (High QPS)](#7-cdc-consumption-high-qps)
8. [Multi-Datasource](#8-multi-datasource)
9. [Event Purge](#9-event-purge)
10. [Distributed Tracing (OpenTelemetry)](#10-distributed-tracing-opentelemetry)
11. [Dead Event Management](#11-dead-event-management)
12. [Micrometer Metrics](#12-micrometer-metrics)
13. [Custom JsonCodec](#13-custom-jsoncodec)
14. [Ordered Delivery](#14-ordered-delivery)

---

## 1. Outbox Table Schemas

Before writing any code, create the `outbox_event` table in your database. Canonical schema files ship in the `outbox-jdbc` JAR under `schema/`:

| Database   | File                                                             | Key types                                            |
|------------|------------------------------------------------------------------|------------------------------------------------------|
| H2         | [`h2.sql`](outbox-jdbc/src/main/resources/schema/h2.sql)               | `CLOB`, `TIMESTAMP`, `TINYINT`                |
| MySQL 8    | [`mysql.sql`](outbox-jdbc/src/main/resources/schema/mysql.sql)         | `JSON`, `DATETIME(6)`, `TINYINT`              |
| PostgreSQL | [`postgresql.sql`](outbox-jdbc/src/main/resources/schema/postgresql.sql) | `JSONB`, `TIMESTAMPTZ`, `SMALLINT`          |

<details>
<summary>MySQL 8 example</summary>

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

</details>

<details>
<summary>PostgreSQL example</summary>

```sql
CREATE TABLE outbox_event (
  event_id VARCHAR(36) PRIMARY KEY,
  event_type VARCHAR(128) NOT NULL,
  aggregate_type VARCHAR(64),
  aggregate_id VARCHAR(128),
  tenant_id VARCHAR(64),
  payload JSONB NOT NULL,
  headers JSONB,
  status SMALLINT NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  available_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  done_at TIMESTAMPTZ,
  last_error TEXT,
  locked_by VARCHAR(128),
  locked_at TIMESTAMPTZ
);

CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at);
```

</details>

---

## 2. Quick Start (Composite Builder)

The simplest way to set up the outbox — `Outbox.singleNode()` wires the dispatcher,
poller, and writer into a single `AutoCloseable`:

```java
import outbox.Outbox;
import outbox.OutboxWriter;
import outbox.EventEnvelope;
import outbox.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.JdbcOutboxStores;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;

import javax.sql.DataSource;

DataSource dataSource = /* your DataSource */;

var outboxStore = JdbcOutboxStores.detect(dataSource);
var connectionProvider = new DataSourceConnectionProvider(dataSource);
var txContext = new ThreadLocalTxContext();
var registry = new DefaultListenerRegistry()
    .register("UserCreated", event -> {
      // publish to MQ; include event.eventId() for dedupe
    });

try (Outbox outbox = Outbox.singleNode()
    .connectionProvider(connectionProvider)
    .txContext(txContext)
    .outboxStore(outboxStore)
    .listenerRegistry(registry)
    .build()) {

  var txManager = new JdbcTransactionManager(connectionProvider, txContext);
  OutboxWriter writer = outbox.writer();

  try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
    writer.write("UserCreated", "{\"id\":123}");
    tx.commit();
  }
}
```

<details>
<summary>Manual wiring (advanced)</summary>

For custom `InFlightTracker`, per-component lifecycle, or other advanced scenarios,
wire the components individually:

```java
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.spi.MetricsExporter;
import outbox.dispatch.DispatcherWriterHook;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.JdbcOutboxStores;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;

import javax.sql.DataSource;
import java.time.Duration;

DataSource dataSource = /* your DataSource */;

var outboxStore = JdbcOutboxStores.detect(dataSource);
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
ThreadLocalTxContext txContext = new ThreadLocalTxContext();

OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("UserCreated", event -> {
          // publish to MQ; include event.eventId() for dedupe
        }))
    .interceptor(EventInterceptor.before(event ->
        System.out.println("Dispatching: " + event.eventType())))
    .build();

OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .skipRecent(Duration.ofMillis(1000))
    .batchSize(200)
    .intervalMs(5000)
    .build();

poller.start();

JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);
OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));

try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
  writer.write("UserCreated", "{\"id\":123}");
  tx.commit();
}
```

</details>

---

## 3. Full End-to-End Example (H2 In-Memory)

```java
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.spi.MetricsExporter;
import outbox.dispatch.DispatcherWriterHook;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.H2OutboxStore;
import outbox.jdbc.tx.JdbcTransactionManager;
import outbox.jdbc.tx.ThreadLocalTxContext;

import org.h2.jdbcx.JdbcDataSource;

import java.sql.Connection;
import java.time.Duration;

public final class OutboxExample {
  public static void main(String[] args) throws Exception {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:outbox;MODE=MySQL;DB_CLOSE_DELAY=-1");

    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute(
          "CREATE TABLE outbox_event (" +
              "event_id VARCHAR(36) PRIMARY KEY," +
              "event_type VARCHAR(128) NOT NULL," +
              "aggregate_type VARCHAR(64)," +
              "aggregate_id VARCHAR(128)," +
              "tenant_id VARCHAR(64)," +
              "payload CLOB NOT NULL," +
              "headers CLOB," +
              "status TINYINT NOT NULL," +
              "attempts INT NOT NULL DEFAULT 0," +
              "available_at TIMESTAMP NOT NULL," +
              "created_at TIMESTAMP NOT NULL," +
              "done_at TIMESTAMP," +
              "last_error CLOB," +
              "locked_by VARCHAR(128)," +
              "locked_at TIMESTAMP" +
              ")"
      );
      conn.createStatement().execute(
          "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)"
      );
    }

    var outboxStore = new H2OutboxStore();
    DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
    ThreadLocalTxContext txContext = new ThreadLocalTxContext();
    JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);

    OutboxDispatcher dispatcher = OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .listenerRegistry(new DefaultListenerRegistry()
            .register("UserCreated", event ->
                System.out.println("Published to MQ: " + event.eventId())))
        .workerCount(2)
        .hotQueueCapacity(100)
        .coldQueueCapacity(100)
        .build();

    OutboxPoller poller = OutboxPoller.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .handler(new DispatcherPollerHandler(dispatcher))
        .skipRecent(Duration.ofMillis(500))
        .batchSize(50)
        .intervalMs(1000)
        .build();
    poller.start();

    OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));

    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      writer.write("UserCreated", "{\"id\":123}");
      tx.commit();
    }

    Thread.sleep(500);
    poller.close();
    dispatcher.close();
  }
}
```

---

## 4. Type-Safe Event + Aggregate Types

You can use enums (or any class) implementing `EventType` and `AggregateType` for compile-time safety:

```java
import outbox.AggregateType;
import outbox.EventEnvelope;
import outbox.EventType;

enum UserEvents implements EventType {
  USER_CREATED;

  @Override
  public String name() {
    return name();
  }
}

enum Aggregates implements AggregateType {
  USER;

  @Override
  public String name() {
    return name();
  }
}

EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
    .aggregateType(Aggregates.USER)
    .aggregateId("user-123")
    .payloadJson("{\"id\":123}")
    .build();
```

Header keys must be non-null when setting `headers(...)`.

---

## 5. Spring Integration

The `outbox-spring-adapter` module provides `SpringTxContext`, which hooks into Spring's transaction lifecycle so that `afterCommit` callbacks fire naturally after `@Transactional` methods complete.
`SpringTxContext` requires Spring transaction synchronization to be active when registering `afterCommit`/`afterRollback` callbacks.

### Configuration

Wire all outbox beans in a `@Configuration` class:

```java
import outbox.OutboxWriter;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.DispatcherWriterHook;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.TxContext;
import outbox.spring.SpringTxContext;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.JdbcOutboxStores;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

  @Bean
  public AbstractJdbcOutboxStore outboxStore(DataSource dataSource) {
    return JdbcOutboxStores.detect(dataSource);
  }

  @Bean
  public DataSourceConnectionProvider connectionProvider(DataSource dataSource) {
    return new DataSourceConnectionProvider(dataSource);
  }

  @Bean
  public TxContext txContext(DataSource dataSource) {
    return new SpringTxContext(dataSource);
  }

  @Bean
  public DefaultListenerRegistry listenerRegistry() {
    return new DefaultListenerRegistry()
        .register("User", "UserCreated", event ->
            log.info("User created: id={}, payload={}",
                event.eventId(), event.payloadJson()))
        .register("Order", "OrderPlaced", event ->
            log.info("Order placed: id={}, payload={}",
                event.eventId(), event.payloadJson()));
  }

  @Bean(destroyMethod = "close")
  public OutboxDispatcher dispatcher(
      DataSourceConnectionProvider connectionProvider,
      AbstractJdbcOutboxStore outboxStore,
      DefaultListenerRegistry listenerRegistry) {
    return OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .listenerRegistry(listenerRegistry)
        .inFlightTracker(new DefaultInFlightTracker(30_000))
        .workerCount(2)
        .interceptor(EventInterceptor.before(event ->
            log.info("[Audit] Dispatching: type={}, aggregateId={}",
                event.eventType(), event.aggregateId())))
        .build();
  }

  @Bean(destroyMethod = "close")
  public OutboxPoller poller(
      DataSourceConnectionProvider connectionProvider,
      AbstractJdbcOutboxStore outboxStore,
      OutboxDispatcher dispatcher) {
    OutboxPoller poller = OutboxPoller.builder()
        .connectionProvider(connectionProvider)
        .outboxStore(outboxStore)
        .handler(new DispatcherPollerHandler(dispatcher))
        .skipRecent(Duration.ofMillis(500))
        .batchSize(100)
        .intervalMs(5000)
        .build();
    poller.start();
    return poller;
  }

  @Bean
  public OutboxWriter outboxWriter(
      TxContext txContext,
      AbstractJdbcOutboxStore outboxStore,
      OutboxDispatcher dispatcher) {
    return new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));
  }
}
```

### Publishing Events from @Transactional Methods

Inject `OutboxWriter` into any Spring-managed bean and call `write()` inside a `@Transactional` method. The event is inserted within the same database transaction as your business logic. After Spring commits, the `DispatcherWriterHook` fires automatically.

```java
@RestController
@RequestMapping("/events")
public class EventController {

  private final OutboxWriter outboxWriter;

  public EventController(OutboxWriter outboxWriter) {
    this.outboxWriter = outboxWriter;
  }

  @PostMapping("/user-created")
  @Transactional
  public Map<String, Object> userCreated(@RequestParam String name) {
    String userId = UUID.randomUUID().toString().substring(0, 8);
    String eventId = outboxWriter.write(
        EventEnvelope.builder("UserCreated")
            .aggregateType("User")
            .aggregateId(userId)
            .headers(Map.of("source", "api"))
            .payloadJson("{\"userId\":\"" + userId + "\",\"name\":\"" + name + "\"}")
            .build());
    return Map.of("status", "ok", "eventId", eventId, "userId", userId);
  }
}
```

### Running the Spring Demo

```bash
mvn install -DskipTests && mvn -f samples/outbox-spring-demo/pom.xml spring-boot:run
```

Test with:

```bash
curl -X POST 'http://localhost:8080/events/user-created?name=Alice'
curl -X POST 'http://localhost:8080/events/order-placed?orderId=123'
curl http://localhost:8080/events
```

---

## 6. Poller Event Locking

For multi-instance deployments, enable claim-based locking so pollers don't compete for the same events. OutboxPoller requires a handler; use `DispatcherPollerHandler` with the built-in dispatcher.

```java
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .skipRecent(Duration.ofMillis(1000))
    .batchSize(200)
    .intervalMs(5000)
    .claimLocking("poller-node-1", Duration.ofMinutes(5))
    .build();
```

- Each poller claims events by setting `locked_by`/`locked_at` columns
- Expired locks (older than the configured timeout) are automatically reclaimed
- Locks are cleared when events reach DONE, RETRY, or DEAD status
- Omit `claimLocking` from the builder for single-node mode (no locking)

---

## 7. CDC Consumption (High QPS)

For high-throughput workloads, you can disable the in-process poller and use CDC to consume the outbox table.

1. Do not start `OutboxPoller`.
2. Create `OutboxWriter` without a hook (or with `WriterHook.NOOP`) to skip hot-path enqueue.
3. Use CDC to read `outbox_event` inserts and publish downstream; dedupe by `event_id`.
4. Status updates are optional in CDC-only mode. If you do not mark DONE, treat the table as append-only and enforce retention (e.g., partitioning + TTL).

### Using the composite builder (recommended)

```java
// Simplest CDC setup — writer only, no purge
try (Outbox outbox = Outbox.writerOnly()
    .txContext(txContext)
    .outboxStore(outboxStore)
    .build()) {
  OutboxWriter writer = outbox.writer();
  // write events inside transactions; CDC reads the table externally
}

// CDC with age-based purge — deletes ALL events older than retention, regardless of status
try (Outbox outbox = Outbox.writerOnly()
    .txContext(txContext)
    .outboxStore(outboxStore)
    .connectionProvider(connectionProvider)
    .purger(new H2AgeBasedPurger())           // or MySqlAgeBasedPurger, PostgresAgeBasedPurger
    .purgeRetention(Duration.ofHours(24))      // default: 7 days
    .purgeIntervalSeconds(1800)                // default: 1 hour
    .build()) {
  OutboxWriter writer = outbox.writer();
}
```

### Manual wiring (advanced)

```java
import outbox.OutboxWriter;
import outbox.WriterHook;

OutboxWriter writer = new OutboxWriter(txContext, outboxStore);
// or: new OutboxWriter(txContext, outboxStore, WriterHook.NOOP)
```

If you enable both `DispatcherWriterHook` and CDC, you must dedupe downstream or choose one primary delivery path.

---

## 8. Multi-Datasource

The outbox pattern requires the `outbox_event` table to live in the **same database** as the business data so that publishes are transactionally atomic. When your system spans multiple databases, each datasource needs its own full outbox stack. Stateless `EventListener` and `EventInterceptor` instances can be shared across stacks, but each stack gets its own `ListenerRegistry` (the per-stack routing table).

### Per-Stack Components

Each datasource needs all of these, completely independent of other stacks:

| Component | Purpose |
|-----------|---------|
| `DataSource` | Database connection pool |
| `DataSourceConnectionProvider` | Wraps DataSource for outbox components |
| `OutboxStore` | Auto-detected via `JdbcOutboxStores.detect()` |
| `ThreadLocalTxContext` | Transaction lifecycle hooks |
| `JdbcTransactionManager` | Manages JDBC transactions |
| `DefaultListenerRegistry` | Per-stack event routing table |
| `OutboxDispatcher` | Worker threads and queues |
| `OutboxPoller` | Fallback polling loop |
| `OutboxWriter` | Event publishing API |

### Example: Orders + Inventory Stacks

```java
// --- Shared stateless listener (safe to reuse) ---
EventListener sharedListener = event ->
    System.out.printf("[%s/%s] eventId=%s payload=%s%n",
        event.aggregateType(), event.eventType(),
        event.eventId(), event.payloadJson());

// --- Orders stack ---
DataSource ordersDs = createDataSource("orders");
var ordersOutboxStore = JdbcOutboxStores.detect(ordersDs);
var ordersConn = new DataSourceConnectionProvider(ordersDs);
var ordersTx = new ThreadLocalTxContext();

OutboxDispatcher ordersDispatcher = OutboxDispatcher.builder()
    .connectionProvider(ordersConn)
    .outboxStore(ordersOutboxStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("Order", "OrderPlaced", sharedListener)
        .register("Order", "OrderShipped", sharedListener))
    .build();

OutboxPoller ordersPoller = OutboxPoller.builder()
    .connectionProvider(ordersConn)
    .outboxStore(ordersOutboxStore)
    .handler(new DispatcherPollerHandler(ordersDispatcher))
    .skipRecent(Duration.ofMillis(500))
    .batchSize(50)
    .intervalMs(1000)
    .build();
ordersPoller.start();

var ordersTxManager = new JdbcTransactionManager(ordersConn, ordersTx);
var ordersWriter = new OutboxWriter(ordersTx, ordersOutboxStore,
    new DispatcherWriterHook(ordersDispatcher));

// --- Inventory stack (same pattern, different datasource) ---
DataSource inventoryDs = createDataSource("inventory");
var invOutboxStore = JdbcOutboxStores.detect(inventoryDs);
var invConn = new DataSourceConnectionProvider(inventoryDs);
var invTx = new ThreadLocalTxContext();

OutboxDispatcher invDispatcher = OutboxDispatcher.builder()
    .connectionProvider(invConn)
    .outboxStore(invOutboxStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("Inventory", "StockReserved", sharedListener)
        .register("Inventory", "StockDepleted", sharedListener))
    .build();

OutboxPoller invPoller = OutboxPoller.builder()
    .connectionProvider(invConn)
    .outboxStore(invOutboxStore)
    .handler(new DispatcherPollerHandler(invDispatcher))
    .skipRecent(Duration.ofMillis(500))
    .batchSize(50)
    .intervalMs(1000)
    .build();
invPoller.start();

var invTxManager = new JdbcTransactionManager(invConn, invTx);
var invWriter = new OutboxWriter(invTx, invOutboxStore,
    new DispatcherWriterHook(invDispatcher));

// --- Publish to each stack independently ---
try (var tx = ordersTxManager.begin()) {
  ordersWriter.write(EventEnvelope.builder("OrderPlaced")
      .aggregateType("Order").aggregateId("order-1")
      .payloadJson("{\"item\":\"widget\",\"qty\":5}")
      .build());
  tx.commit();
}

try (var tx = invTxManager.begin()) {
  invWriter.write(EventEnvelope.builder("StockReserved")
      .aggregateType("Inventory").aggregateId("widget")
      .payloadJson("{\"qty\":5}")
      .build());
  tx.commit();
}
```

Each stack is completely independent -- separate worker threads, separate pollers, separate transaction managers. The only shared component is the stateless listener.

### Running the Multi-Datasource Demo

```bash
mvn install -DskipTests && mvn -pl samples/outbox-multi-ds-demo exec:java
```

---

## 9. Event Purge

The outbox table is a transient buffer, not an outbox store. Over time, terminal events (DONE, DEAD) accumulate and degrade poller query performance. `OutboxPurgeScheduler` periodically deletes these old events.

### Basic Setup

```java
import outbox.purge.OutboxPurgeScheduler;
import outbox.jdbc.purge.H2EventPurger;   // or MySqlEventPurger, PostgresEventPurger
import outbox.jdbc.DataSourceConnectionProvider;

import java.time.Duration;

DataSource dataSource = /* your DataSource */;
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);

OutboxPurgeScheduler purgeScheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)
    .purger(new H2EventPurger())       // match your database
    .retention(Duration.ofDays(7))     // delete DONE/DEAD events older than 7 days
    .batchSize(500)                    // rows per batch (limits lock duration)
    .intervalSeconds(3600)             // run every hour
    .build();

purgeScheduler.start();

// ... application runs ...

purgeScheduler.close();  // clean shutdown
```

After calling `close()`, `start()` cannot be called again and throws `IllegalStateException`.

### Choosing the Right Purger

| Database   | Purger Class         |
|------------|----------------------|
| H2         | `H2EventPurger`      |
| MySQL/TiDB | `MySqlEventPurger`   |
| PostgreSQL | `PostgresEventPurger`|

All purger classes support a custom table name: `new H2EventPurger("custom_outbox")`.

### One-Off Purge

You can trigger a single purge cycle without starting the scheduler:

```java
OutboxPurgeScheduler purgeScheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)
    .purger(new H2EventPurger())
    .retention(Duration.ofDays(30))
    .build();

purgeScheduler.runOnce();  // purge now, then discard
purgeScheduler.close();
```

### Spring Integration

Add the purge scheduler as a Spring bean alongside your existing outbox configuration:

```java
@Bean(destroyMethod = "close")
public OutboxPurgeScheduler purgeScheduler(
    DataSourceConnectionProvider connectionProvider,
    AbstractJdbcOutboxStore outboxStore) {
  // Pick the purger matching your database
  var purger = new MySqlEventPurger();

  OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
      .connectionProvider(connectionProvider)
      .purger(purger)
      .retention(Duration.ofDays(14))
      .batchSize(1000)
      .intervalSeconds(1800)  // every 30 minutes
      .build();
  scheduler.start();
  return scheduler;
}
```

### How It Works

1. Every `intervalSeconds`, the scheduler calculates `cutoff = now - retention`
2. It deletes terminal events (status DONE or DEAD) where `COALESCE(done_at, created_at) < cutoff`
3. Deletion happens in batches of `batchSize`, each on its own auto-committed connection
4. Batching continues until a batch deletes fewer than `batchSize` rows (backlog drained)
5. Active events (NEW, RETRY) are never touched

### Important Notes

- **Archive first**: If you need audit trails, archive events in your `EventListener` before they age past the retention period
- **No schema changes**: The purger works with the existing `outbox_event` table -- no new tables or columns needed
- **Safe**: Only terminal events (DONE=1, DEAD=3) are deleted; active events (NEW=0, RETRY=2) are never affected

---

## 10. Distributed Tracing (OpenTelemetry)

The outbox framework does not bundle OpenTelemetry dependencies, but the existing `EventEnvelope.headers` map and `EventInterceptor` hook provide everything needed to propagate trace context across the async boundary.

### Writer Side: Inject Trace Context

When writing an event, inject the W3C `traceparent` header from the current span:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

// Capture current trace context into headers
Map<String, String> headers = new HashMap<>();
GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
    .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));

EventEnvelope envelope = EventEnvelope.builder("OrderPlaced")
    .aggregateType("Order")
    .aggregateId("order-123")
    .headers(headers)  // includes traceparent, tracestate
    .payloadJson("{\"item\":\"widget\"}")
    .build();

writer.write(envelope);
```

### Dispatcher Side: Extract and Link

Register an `EventInterceptor` that extracts the trace context from event headers and creates a linked span:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import outbox.dispatch.EventInterceptor;
import outbox.model.OutboxEvent;
import outbox.util.JsonCodec;

Tracer tracer = GlobalOpenTelemetry.getTracer("outbox-dispatcher");

// Thread-local to pass Scope from beforeDispatch to afterDispatch
ThreadLocal<Scope> scopeHolder = new ThreadLocal<>();

EventInterceptor tracingInterceptor = new EventInterceptor() {
  @Override
  public void beforeDispatch(OutboxEvent event) {
    // Parse headers from JSON
    Map<String, String> headers = JsonCodec.getDefault().parseObject(event.headersJson());

    // Extract upstream trace context
    Context extracted = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
        .extract(Context.current(), headers, new TextMapGetter<>() {
          @Override
          public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
          }

          @Override
          public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
          }
        });

    // Start a new span linked to the producer trace
    Span span = tracer.spanBuilder("outbox.dispatch " + event.eventType())
        .setParent(extracted)
        .setSpanKind(SpanKind.CONSUMER)
        .setAttribute("outbox.event_id", event.eventId())
        .setAttribute("outbox.event_type", event.eventType())
        .setAttribute("outbox.aggregate_type", event.aggregateType())
        .startSpan();

    // makeCurrent() returns a Scope that must be closed to restore the previous context
    scopeHolder.set(span.makeCurrent());
  }

  @Override
  public void afterDispatch(OutboxEvent event, Throwable error) {
    Span span = Span.current();
    if (error != null) {
      span.recordException(error);
    }
    span.end();
    Scope scope = scopeHolder.get();
    if (scope != null) {
      scope.close();
      scopeHolder.remove();
    }
  }
};
```

### Wiring

Register the tracing interceptor **first** on the dispatcher builder so it wraps all other interceptors:

```java
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .listenerRegistry(registry)
    .interceptor(tracingInterceptor)          // tracing first
    .interceptor(EventInterceptor.before(e -> // then logging, audit, etc.
        log.info("Dispatching: {}", e.eventType())))
    .build();
```

This gives you end-to-end traces from the writer through the async outbox dispatch, with no framework-level OTel dependency.

---

## 11. Dead Event Management

Events that exceed `maxAttempts` or have no registered listener end up in DEAD status. `DeadEventManager` provides a connection-managed facade to query, replay, and count these events.

### Setup

```java
import outbox.dead.DeadEventManager;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.JdbcOutboxStores;

DataSource dataSource = /* your DataSource */;
var connectionProvider = new DataSourceConnectionProvider(dataSource);
var outboxStore = JdbcOutboxStores.detect(dataSource);

DeadEventManager deadEvents = new DeadEventManager(connectionProvider, outboxStore);
```

### Query Dead Events

```java
// All dead events (up to 100)
var all = deadEvents.query(null, null, 100);

// Filter by event type
var userErrors = deadEvents.query("UserCreated", null, 50);

// Filter by both event type and aggregate type
var orderErrors = deadEvents.query("OrderPlaced", "Order", 50);
```

### Replay a Single Event

```java
boolean replayed = deadEvents.replay("01HXYZ...");  // event ID
if (replayed) {
  System.out.println("Event reset to NEW, will be reprocessed");
}
```

### Batch Replay

```java
// Replay all dead "UserCreated" events in batches of 100
int total = deadEvents.replayAll("UserCreated", null, 100);
System.out.println("Replayed " + total + " events");

// Replay ALL dead events
int allReplayed = deadEvents.replayAll(null, null, 100);
```

### Count Dead Events

```java
int total = deadEvents.count(null);              // all dead events
int userCount = deadEvents.count("UserCreated"); // by event type
```

### Spring Integration

```java
@Bean
public DeadEventManager deadEventManager(
    DataSourceConnectionProvider connectionProvider,
    AbstractJdbcOutboxStore outboxStore) {
  return new DeadEventManager(connectionProvider, outboxStore);
}
```

All methods handle `SQLException` internally — they log at SEVERE and return safe defaults (`List.of()`, `false`, `0`) rather than throwing.

---

## 12. Micrometer Metrics

The `outbox-micrometer` module provides `MicrometerMetricsExporter`, which registers counters and gauges with a Micrometer `MeterRegistry` for export to Prometheus, Grafana, Datadog, etc.

### Add Dependency

```xml
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-micrometer</artifactId>
  <version>0.6.0</version>
</dependency>
```

### Wire to Dispatcher

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import outbox.micrometer.MicrometerMetricsExporter;

MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
MicrometerMetricsExporter metrics = new MicrometerMetricsExporter(registry);

OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .listenerRegistry(listenerRegistry)
    .metrics(metrics)  // plug in metrics
    .build();

OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .metrics(metrics)  // same exporter for poller
    .build();
```

### Spring Boot Auto-Configuration

In Spring Boot with `spring-boot-starter-actuator`, the `MeterRegistry` is auto-configured:

```java
@Bean
public MicrometerMetricsExporter outboxMetrics(MeterRegistry registry) {
  return new MicrometerMetricsExporter(registry);
}

@Bean(destroyMethod = "close")
public OutboxDispatcher dispatcher(
    DataSourceConnectionProvider connectionProvider,
    AbstractJdbcOutboxStore outboxStore,
    DefaultListenerRegistry listenerRegistry,
    MicrometerMetricsExporter metrics) {
  return OutboxDispatcher.builder()
      .connectionProvider(connectionProvider)
      .outboxStore(outboxStore)
      .listenerRegistry(listenerRegistry)
      .metrics(metrics)
      .build();
}
```

Metrics are then available at `/actuator/prometheus`.

### Metric Names

**Counters:**

| Name | Description |
|------|-------------|
| `outbox.enqueue.hot` | Events enqueued via hot path |
| `outbox.enqueue.hot.dropped` | Events dropped (hot queue full) |
| `outbox.enqueue.cold` | Events enqueued via cold (poller) path |
| `outbox.dispatch.success` | Events dispatched successfully |
| `outbox.dispatch.failure` | Events failed (will retry) |
| `outbox.dispatch.dead` | Events moved to DEAD |

**Gauges:**

| Name | Description |
|------|-------------|
| `outbox.queue.hot.depth` | Current hot queue depth |
| `outbox.queue.cold.depth` | Current cold queue depth |
| `outbox.lag.oldest.ms` | Lag of oldest pending event (ms) |

### Multi-Instance Name Prefix

When running multiple outbox stacks, use a custom prefix to avoid metric collisions:

```java
// Orders stack
var ordersMetrics = new MicrometerMetricsExporter(registry, "orders.outbox");

// Inventory stack
var inventoryMetrics = new MicrometerMetricsExporter(registry, "inventory.outbox");
```

This produces metrics like `orders.outbox.dispatch.success` and `inventory.outbox.dispatch.success`.

---

## 13. Custom JsonCodec

The framework uses `JsonCodec` to encode/decode event header maps (`Map<String, String>`) to/from JSON. The built-in `DefaultJsonCodec` is a lightweight, zero-dependency implementation. If you already have Jackson or Gson on your classpath, you can replace it for better performance or compatibility.

### When to Replace DefaultJsonCodec

- You want to use your existing Jackson/Gson `ObjectMapper` for consistency
- You need better performance for very large header maps
- You want to leverage Jackson's streaming parser

### Implement the Interface

```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import outbox.util.JsonCodec;

import java.util.Collections;
import java.util.Map;

public class JacksonJsonCodec implements JsonCodec {
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

  @Override
  public String toJson(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) return null;
    try {
      return mapper.writeValueAsString(headers);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to encode headers", e);
    }
  }

  @Override
  public Map<String, String> parseObject(String json) {
    if (json == null || json.isBlank() || "null".equals(json.trim())) {
      return Collections.emptyMap();
    }
    try {
      return mapper.readValue(json, MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse headers JSON", e);
    }
  }
}
```

### Inject into Components

The custom codec can be injected at three points:

```java
JsonCodec codec = new JacksonJsonCodec();

// 1. OutboxStore (used for insertNew and poll/claim row mapping)
var outboxStore = new H2OutboxStore("outbox_event", codec);

// 2. OutboxPoller (used when converting OutboxEvent → EventEnvelope)
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(handler)
    .jsonCodec(codec)
    .build();

// 3. Auto-detection with custom codec
var autoStore = JdbcOutboxStores.detect(dataSource, codec);
```

If you don't inject a codec, `JsonCodec.getDefault()` (the built-in `DefaultJsonCodec` singleton) is used everywhere.

---

## 14. Ordered Delivery

For use cases requiring per-aggregate FIFO ordering (e.g., `OrderCreated` must be delivered before `OrderShipped`), use the **poller-only** mode with a single dispatch worker.

### Why This Works

The dual hot+cold path architecture makes ordering hard — events for the same aggregate can arrive via different paths in unpredictable order. By disabling the hot path and using a single worker, the poller reads events in `ORDER BY created_at` order and the worker delivers them sequentially. Since DB I/O (polling) is the throughput bottleneck, a single worker easily keeps up.

**Caveat — retries break ordering.** If event A fails and enters backoff retry, its `available_at` is set to a future time. Event B (same aggregate, inserted later) will be polled and delivered while A waits — breaking order. Set `maxAttempts(1)` so failed events go straight to DEAD. Use [Dead Event Management](#11-dead-event-management) to replay them after fixing the issue.

### Configuration (Composite Builder)

```java
ListenerRegistry registry = new DefaultListenerRegistry();
registry.register(
    new StringAggregateType("Order"),
    new StringEventType("OrderCreated"),
    event -> System.out.println("Order created: " + event.payload())
);
registry.register(
    new StringAggregateType("Order"),
    new StringEventType("OrderShipped"),
    event -> System.out.println("Order shipped: " + event.payload())
);

// Outbox.ordered() forces workerCount=1, maxAttempts=1, no WriterHook
try (Outbox outbox = Outbox.ordered()
    .connectionProvider(connectionProvider)
    .txContext(txContext)
    .outboxStore(outboxStore)
    .listenerRegistry(registry)
    .intervalMs(1000)
    .build()) {
  OutboxWriter writer = outbox.writer();
  // write events inside transactions...
}
```

<details>
<summary>Manual wiring</summary>

```java
ListenerRegistry registry = new DefaultListenerRegistry();
registry.register(
    new StringAggregateType("Order"),
    new StringEventType("OrderCreated"),
    event -> System.out.println("Order created: " + event.payload())
);
registry.register(
    new StringAggregateType("Order"),
    new StringEventType("OrderShipped"),
    event -> System.out.println("Order shipped: " + event.payload())
);

// Dispatcher with single worker — sequential dispatch preserves poll order
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .listenerRegistry(registry)
    .workerCount(1)    // single worker → sequential dispatch
    .maxAttempts(1)    // no retry → strict ordering (failed events go DEAD)
    .build();

// No DispatcherWriterHook — hot path disabled
OutboxWriter writer = new OutboxWriter(txContext, outboxStore);

// Single-node poller reads in DB insertion order
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .intervalMs(1000)
    .build();
poller.start();
```

</details>

### Trade-offs

| | Hot + Poller (default) | Poller-only, single worker |
|---|---|---|
| **Latency** | Sub-second (hot path) | Bounded by poll interval |
| **Ordering** | No guarantee | Per-aggregate FIFO |
| **Throughput** | Parallel workers | Single-threaded |

For unordered events, use the default hot + poller mode for lowest latency.
