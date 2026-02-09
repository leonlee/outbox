# outbox-java

Minimal, Spring-free outbox framework with JDBC persistence, hot-path enqueue, and poller fallback.

## Modules

- `outbox-core`: core APIs, dispatcher, poller, and registries.
- `outbox-jdbc`: JDBC event store and transaction helpers.
- `outbox-spring-adapter`: optional `TxContext` implementation for Spring.
- `samples/outbox-demo`: minimal, non-Spring demo (H2).
- `samples/outbox-spring-demo`: Spring demo app.
- `samples/outbox-multi-ds-demo`: multi-datasource demo (two H2 databases).

## Architecture

```text
  +-----------------------+        write()        +---------------+
  | Application / Domain | ----------------------> | OutboxWriter  |
  +-----------------------+                         +-------+-------+
                                                    | insert
                                                    v
                                            +--------------------+
                                            |    EventStore      |
                                            +---------+----------+
                                                      | persist
                                                      v
                                            +--------------------+
                                            |   Outbox Table     |
                                            +--------------------+

   enqueue hot                                      poll pending
      |                                                  ^
      v                                                  |
  +-----------+                                       +--------------+
  | Hot Queue |                                       | OutboxPoller |
  +-----+-----+                                       +------+-------+
        |                                                    |
        v                                                    | enqueue cold
  +------------------+                                       |
  | OutboxDispatcher | <-------------------------------------+
  +--------+---------+
           |
           v
  +------------------+     onEvent()      +------------+
  | ListenerRegistry | ----------------> | Listener A |
  +--------+---------+                   +------------+
           |                             +------------+
           +--------------------------> | Listener B |
                                         +------------+

  OutboxDispatcher ---> mark DONE/RETRY/DEAD ---> EventStore
```

## Quick Start (Manual JDBC)

```java
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.spi.MetricsExporter;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.JdbcEventStores;
import outbox.jdbc.JdbcTransactionManager;
import outbox.jdbc.ThreadLocalTxContext;

import javax.sql.DataSource;
import java.time.Duration;

DataSource dataSource = /* your DataSource */;

var eventStore = JdbcEventStores.detect(dataSource);
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
ThreadLocalTxContext txContext = new ThreadLocalTxContext();

OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .eventStore(eventStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("UserCreated", event -> {
          // publish to MQ; include event.eventId() for dedupe
        }))
    .interceptor(EventInterceptor.before(event ->
        System.out.println("Dispatching: " + event.eventType())))
    .build();

OutboxPoller poller = new OutboxPoller(
    connectionProvider,
    eventStore,
    dispatcher,
    Duration.ofMillis(1000),
    200,
    5000,
    MetricsExporter.NOOP
);

poller.start();

JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);
OutboxWriter writer = new OutboxWriter(txContext, eventStore, dispatcher);

try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
  writer.write("UserCreated", "{\"id\":123}");
  tx.commit();
}
```

## Full End-to-End Example (H2 In-Memory)

```java
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.spi.MetricsExporter;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.H2EventStore;
import outbox.jdbc.JdbcTransactionManager;
import outbox.jdbc.ThreadLocalTxContext;

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

    var eventStore = new H2EventStore();
    DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
    ThreadLocalTxContext txContext = new ThreadLocalTxContext();
    JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);

    OutboxDispatcher dispatcher = OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
        .listenerRegistry(new DefaultListenerRegistry()
            .register("UserCreated", event ->
                System.out.println("Published to MQ: " + event.eventId())))
        .workerCount(2)
        .hotQueueCapacity(100)
        .coldQueueCapacity(100)
        .build();

    OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        eventStore,
        dispatcher,
        Duration.ofMillis(500),
        50,
        1000,
        MetricsExporter.NOOP
    );
    poller.start();

    OutboxWriter writer = new OutboxWriter(txContext, eventStore, dispatcher);

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

## Spring Integration (Adapter Only)

```java
import outbox.spring.SpringTxContext;

SpringTxContext txContext = new SpringTxContext(dataSource);
// Use OutboxWriter with this TxContext
```

## Type-safe Event + Aggregate Types (Optional)

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

## Outbox Table

Canonical schema files ship in the `outbox-jdbc` JAR under `schema/`:

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

## Requirements

- Java 17 or later

## Poller Event Locking

For multi-instance deployments, enable claim-based locking so pollers don't compete for the same events:

```java
OutboxPoller poller = new OutboxPoller(
    connectionProvider,
    eventStore,
    dispatcher,
    Duration.ofMillis(1000),  // skipRecent
    200,                       // batchSize
    5000,                      // intervalMs
    MetricsExporter.NOOP,
    "poller-node-1",           // ownerId (or null to auto-generate)
    Duration.ofMinutes(5)      // lockTimeout
);
```

- Each poller claims events by setting `locked_by`/`locked_at` columns
- Expired locks (older than `lockTimeout`) are automatically reclaimed
- Locks are cleared when events reach DONE, RETRY, or DEAD status
- Use the 7-arg constructor (without `ownerId`/`lockTimeout`) for single-instance mode (no locking)

## Multi-Datasource

The outbox pattern requires the `outbox_event` table to live in the **same database** as the business data so that publishes are transactionally atomic. When your system spans multiple databases, each datasource needs its own full stack: `ConnectionProvider`, `EventStore`, `TxContext`, `JdbcTransactionManager`, `ListenerRegistry`, `OutboxDispatcher`, `OutboxPoller`, and `OutboxWriter`. Stateless `EventListener` and `EventInterceptor` instances can be shared across stacks, but each stack gets its own `ListenerRegistry` (the per-stack routing table).

See [`samples/outbox-multi-ds-demo`](samples/outbox-multi-ds-demo) for a complete runnable example with two H2 databases (Orders + Inventory), each with its own outbox stack sharing a stateless listener.

```bash
mvn install -DskipTests && mvn -pl samples/outbox-multi-ds-demo exec:java
```

## Notes

- Delivery is **at-least-once**. Use `eventId` for downstream dedupe.
- Hot queue drops do not throw; the poller will pick up the event.
