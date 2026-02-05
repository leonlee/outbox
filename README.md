# outbox-java

Minimal, Spring-free outbox framework with JDBC persistence, hot-path enqueue, and poller fallback.

## Modules

- `outbox-core`: core APIs, dispatcher, poller, and registries.
- `outbox-jdbc`: JDBC repository and transaction helpers.
- `outbox-spring-adapter`: optional `TxContext` implementation for Spring.
- `outbox-demo`: minimal, non-Spring demo (H2).
- `outbox-spring-demo`: Spring demo app.

## Architecture

```text
  +-----------------------+        publish()        +----------------------+
  | Application / Domain | ----------------------> | DefaultOutboxClient  |
  +-----------------------+                         +----------+-----------+
                                                     | insert
                                                     v
                                             +--------------------+
                                             |  OutboxRepository |
                                             +---------+----------+
                                                       | persist
                                                       v
                                             +--------------------+
                                             |   Outbox Table     |
                                             +--------------------+

   enqueue hot                                      poll pending
      |                                                  ^
      v                                                  |
  +-----------+                                      +-----------+
  | Hot Queue |                                      | Outbox    |
  +-----+-----+                                      | Poller    |
        |                                            +-----+-----+
        v                                                  |
  +------------------+                                     | enqueue cold
  |    Dispatcher    | <-----------------------------------+
  +--------+---------+
           |
           v
  +------------------+     onEvent()      +------------+
  | ListenerRegistry | ----------------> | Listener A |
  +--------+---------+                   +------------+
           |                             +------------+
           +--------------------------> | Listener B |
                                         +------------+

  Dispatcher ---> mark DONE/RETRY/DEAD ---> OutboxRepository
```

## Quick Start (Manual JDBC)

```java
import outbox.core.dispatch.DefaultInFlightTracker;
import outbox.core.client.DefaultOutboxClient;
import outbox.core.dispatch.Dispatcher;
import outbox.core.api.EventEnvelope;
import outbox.core.dispatch.ExponentialBackoffRetryPolicy;
import outbox.core.api.OutboxClient;
import outbox.core.api.OutboxMetrics;
import outbox.core.poller.OutboxPoller;
import outbox.core.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.JdbcOutboxRepository;
import outbox.jdbc.JdbcTransactionManager;
import outbox.jdbc.ThreadLocalTxContext;

import javax.sql.DataSource;
import java.time.Duration;

DataSource dataSource = /* your DataSource */;

JdbcOutboxRepository repository = new JdbcOutboxRepository();
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
ThreadLocalTxContext txContext = new ThreadLocalTxContext();

Dispatcher dispatcher = new Dispatcher(
    connectionProvider,
    repository,
    new DefaultListenerRegistry()
        .register("UserCreated", event -> {
          // publish to MQ; include event.eventId() for dedupe
        }),
    new DefaultInFlightTracker(),
    new ExponentialBackoffRetryPolicy(200, 60_000),
    10, // maxAttempts
    4,  // workerCount
    1000,
    1000,
    OutboxMetrics.NOOP
);

OutboxPoller poller = new OutboxPoller(
    connectionProvider,
    repository,
    dispatcher,
    Duration.ofMillis(1000),
    200,
    5000,
    OutboxMetrics.NOOP
);

poller.start();

JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);

try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
  OutboxClient client = new DefaultOutboxClient(txContext, repository, dispatcher, OutboxMetrics.NOOP);
  client.publish(EventEnvelope.ofJson("UserCreated", "{\"id\":123}"));
  tx.commit();
}
```

## Full End-to-End Example (H2 In-Memory)

```java
import outbox.core.dispatch.DefaultInFlightTracker;
import outbox.core.client.DefaultOutboxClient;
import outbox.core.dispatch.Dispatcher;
import outbox.core.api.EventEnvelope;
import outbox.core.dispatch.ExponentialBackoffRetryPolicy;
import outbox.core.api.OutboxClient;
import outbox.core.api.OutboxMetrics;
import outbox.core.poller.OutboxPoller;
import outbox.core.registry.DefaultListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.JdbcOutboxRepository;
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
              "last_error CLOB" +
              ")"
      );
      conn.createStatement().execute(
          "CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at)"
      );
    }

    JdbcOutboxRepository repository = new JdbcOutboxRepository();
    DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
    ThreadLocalTxContext txContext = new ThreadLocalTxContext();
    JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);

    Dispatcher dispatcher = new Dispatcher(
        connectionProvider,
        repository,
        new DefaultListenerRegistry()
            .register("UserCreated", event ->
                System.out.println("Published to MQ: " + event.eventId())),
        new DefaultInFlightTracker(),
        new ExponentialBackoffRetryPolicy(200, 60_000),
        10,
        2,
        100,
        100,
        OutboxMetrics.NOOP
    );

    OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ofMillis(500),
        50,
        1000,
        OutboxMetrics.NOOP
    );
    poller.start();

    try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
      OutboxClient client = new DefaultOutboxClient(txContext, repository, dispatcher, OutboxMetrics.NOOP);
      client.publish(EventEnvelope.ofJson("UserCreated", "{\"id\":123}"));
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
// Use DefaultOutboxClient with this TxContext
```

## Type-safe Event + Aggregate Types (Optional)

```java
import outbox.core.api.AggregateType;
import outbox.core.api.EventEnvelope;
import outbox.core.api.EventType;

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

## Outbox Table (MySQL 8)

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
  last_error TEXT
);

CREATE INDEX idx_status_available ON outbox_event(status, available_at, created_at);
```

## Requirements

- Java 17 or later

## Notes

- Delivery is **at-least-once**. Use `eventId` for downstream dedupe.
- Hot queue drops do not throw; the poller will pick up the event.
