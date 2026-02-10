[English](TUTORIAL.md) | [中文](TUTORIAL.zh-CN.md)

# Outbox 框架教程

分步指南与可运行的代码示例。

项目目标见 [OBJECTIVE.md](OBJECTIVE.zh-CN.md)，完整技术规范见 [SPEC.md](SPEC.zh-CN.md)。

## 目录

**快速上手**

1. [Outbox 表结构](#1-outbox-表结构)
2. [快速开始（纯 JDBC）](#2-快速开始纯-jdbc)
3. [完整示例（H2 内存库）](#3-完整示例h2-内存库)

**核心功能**

4. [类型安全的 Event 与 Aggregate 类型](#4-类型安全的-event-与-aggregate-类型)
5. [Spring 集成](#5-spring-集成)

**进阶**

6. [Poller 事件锁定](#6-poller-事件锁定)
7. [CDC 消费（高 QPS 场景）](#7-cdc-消费高-qps-场景)
8. [多数据源](#8-多数据源)

---

## 1. Outbox 表结构

使用前先在数据库中创建 `outbox_event` 表。`outbox-jdbc` JAR 里自带 `schema/` 目录，包含各数据库的标准建表语句：

| 数据库     | 文件                                                             | 关键类型                                            |
|------------|------------------------------------------------------------------|------------------------------------------------------|
| H2         | [`h2.sql`](outbox-jdbc/src/main/resources/schema/h2.sql)               | `CLOB`, `TIMESTAMP`, `TINYINT`                |
| MySQL 8    | [`mysql.sql`](outbox-jdbc/src/main/resources/schema/mysql.sql)         | `JSON`, `DATETIME(6)`, `TINYINT`              |
| PostgreSQL | [`postgresql.sql`](outbox-jdbc/src/main/resources/schema/postgresql.sql) | `JSONB`, `TIMESTAMPTZ`, `SMALLINT`          |

<details>
<summary>MySQL 8 示例</summary>

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
<summary>PostgreSQL 示例</summary>

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

## 2. 快速开始（纯 JDBC）

```java
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.spi.MetricsExporter;
import outbox.dispatch.DispatcherCommitHook;
import outbox.dispatch.DispatcherPollerHandler;
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

DataSource dataSource = /* 你的 DataSource */;

var eventStore = JdbcEventStores.detect(dataSource);
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
ThreadLocalTxContext txContext = new ThreadLocalTxContext();

OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .eventStore(eventStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("UserCreated", event -> {
          // 发送到 MQ；用 event.eventId() 做去重
        }))
    .interceptor(EventInterceptor.before(event ->
        System.out.println("正在分发: " + event.eventType())))
    .build();

OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .eventStore(eventStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .skipRecent(Duration.ofMillis(1000))
    .batchSize(200)
    .intervalMs(5000)
    .build();

poller.start();

JdbcTransactionManager txManager = new JdbcTransactionManager(connectionProvider, txContext);
OutboxWriter writer = new OutboxWriter(txContext, eventStore, new DispatcherCommitHook(dispatcher));

try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
  writer.write("UserCreated", "{\"id\":123}");
  tx.commit();
}
```

---

## 3. 完整示例（H2 内存库）

```java
import outbox.EventEnvelope;
import outbox.OutboxWriter;
import outbox.spi.MetricsExporter;
import outbox.dispatch.DispatcherCommitHook;
import outbox.dispatch.DispatcherPollerHandler;
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
                System.out.println("已发送到 MQ: " + event.eventId())))
        .workerCount(2)
        .hotQueueCapacity(100)
        .coldQueueCapacity(100)
        .build();

    OutboxPoller poller = OutboxPoller.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
        .handler(new DispatcherPollerHandler(dispatcher))
        .skipRecent(Duration.ofMillis(500))
        .batchSize(50)
        .intervalMs(1000)
        .build();
    poller.start();

    OutboxWriter writer = new OutboxWriter(txContext, eventStore, new DispatcherCommitHook(dispatcher));

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

## 4. 类型安全的 Event 与 Aggregate 类型

可以用 enum（或任意类）实现 `EventType` 和 `AggregateType` 接口，获得编译期类型检查：

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

---

## 5. Spring 集成

`outbox-spring-adapter` 模块提供了 `SpringTxContext`，接入 Spring 事务生命周期，使 `afterCommit` 回调在 `@Transactional` 方法提交后自动触发。

### Bean 配置

在 `@Configuration` 类中注册所有 outbox 组件：

```java
import outbox.OutboxWriter;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.DispatcherCommitHook;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.TxContext;
import outbox.spring.SpringTxContext;
import outbox.jdbc.AbstractJdbcEventStore;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.JdbcEventStores;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

  @Bean
  public AbstractJdbcEventStore eventStore(DataSource dataSource) {
    return JdbcEventStores.detect(dataSource);
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
            log.info("用户已创建: id={}, payload={}",
                event.eventId(), event.payloadJson()))
        .register("Order", "OrderPlaced", event ->
            log.info("订单已提交: id={}, payload={}",
                event.eventId(), event.payloadJson()));
  }

  @Bean(destroyMethod = "close")
  public OutboxDispatcher dispatcher(
      DataSourceConnectionProvider connectionProvider,
      AbstractJdbcEventStore eventStore,
      DefaultListenerRegistry listenerRegistry) {
    return OutboxDispatcher.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
        .listenerRegistry(listenerRegistry)
        .inFlightTracker(new DefaultInFlightTracker(30_000))
        .workerCount(2)
        .interceptor(EventInterceptor.before(event ->
            log.info("[审计] 正在分发: type={}, aggregateId={}",
                event.eventType(), event.aggregateId())))
        .build();
  }

  @Bean(destroyMethod = "close")
  public OutboxPoller poller(
      DataSourceConnectionProvider connectionProvider,
      AbstractJdbcEventStore eventStore,
      OutboxDispatcher dispatcher) {
    OutboxPoller poller = OutboxPoller.builder()
        .connectionProvider(connectionProvider)
        .eventStore(eventStore)
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
      AbstractJdbcEventStore eventStore,
      OutboxDispatcher dispatcher) {
    return new OutboxWriter(txContext, eventStore, new DispatcherCommitHook(dispatcher));
  }
}
```

### 在 @Transactional 方法中发布事件

注入 `OutboxWriter`，在 `@Transactional` 方法中调用 `write()`。事件写入与业务逻辑共享同一个数据库事务，Spring 提交后 `DispatcherCommitHook` 自动触发。

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
            .header("source", "api")
            .payloadJson("{\"userId\":\"" + userId + "\",\"name\":\"" + name + "\"}")
            .build());
    return Map.of("status", "ok", "eventId", eventId, "userId", userId);
  }
}
```

### 运行 Spring Demo

```bash
mvn install -DskipTests && mvn -f samples/outbox-spring-demo/pom.xml spring-boot:run
```

测试接口：

```bash
curl -X POST 'http://localhost:8080/events/user-created?name=Alice'
curl -X POST 'http://localhost:8080/events/order-placed?orderId=123'
curl http://localhost:8080/events
```

---

## 6. Poller 事件锁定

多实例部署时，开启 claim 锁可防止多个 Poller 抢同一批事件。Poller 需要一个 Handler，可直接用 `DispatcherPollerHandler`。

```java
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .eventStore(eventStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .skipRecent(Duration.ofMillis(1000))
    .batchSize(200)
    .intervalMs(5000)
    .ownerId("poller-node-1")
    .lockTimeout(Duration.ofMinutes(5))
    .build();
```

- 每个 Poller 通过设置 `locked_by`/`locked_at` 列来 claim 事件
- 超过 `lockTimeout` 的锁会被自动回收
- 事件到达 DONE、RETRY 或 DEAD 状态时锁自动释放
- 单实例部署不需要锁，省略 `ownerId`/`lockTimeout` 即可

---

## 7. CDC 消费（高 QPS 场景）

高吞吐场景下，可以关闭进程内 Poller，改用 CDC 消费 outbox 表。

1. 不启动 `OutboxPoller`
2. 创建 `OutboxWriter` 时不传 Hook（或用 `AfterCommitHook.NOOP`），跳过热路径入队
3. 用 CDC 监听 `outbox_event` 的 INSERT，下游按 `event_id` 去重
4. 纯 CDC 模式下可以不更新状态，将表当作 append-only 使用，配合分区 + TTL 做数据清理

```java
import outbox.OutboxWriter;
import outbox.AfterCommitHook;

OutboxWriter writer = new OutboxWriter(txContext, eventStore);
// 或: new OutboxWriter(txContext, eventStore, AfterCommitHook.NOOP)
```

如果同时启用了 `DispatcherCommitHook` 和 CDC，下游需要做去重，或只选其中一条投递路径。

---

## 8. 多数据源

Outbox 模式要求 `outbox_event` 表和业务数据在**同一个数据库**中，保证写入的事务原子性。当系统涉及多个数据库时，每个数据源需要独立的完整 outbox 栈。无状态的 `EventListener` 和 `EventInterceptor` 可以跨栈共享，但每个栈有自己的 `ListenerRegistry`（路由表）。

### 每个栈的组件

每个数据源需要以下全部组件，彼此完全独立：

| 组件 | 用途 |
|-----------|---------|
| `DataSource` | 数据库连接池 |
| `DataSourceConnectionProvider` | 为 outbox 组件封装 DataSource |
| `EventStore` | 通过 `JdbcEventStores.detect()` 自动识别 |
| `ThreadLocalTxContext` | 事务生命周期 Hook |
| `JdbcTransactionManager` | 管理 JDBC 事务 |
| `DefaultListenerRegistry` | 该栈的事件路由表 |
| `OutboxDispatcher` | Worker 线程与队列 |
| `OutboxPoller` | 兜底轮询 |
| `OutboxWriter` | 事件发布 API |

### 示例：订单 + 库存 双栈

```java
// --- 共享的无状态 Listener（可安全复用）---
EventListener sharedListener = event ->
    System.out.printf("[%s/%s] eventId=%s payload=%s%n",
        event.aggregateType(), event.eventType(),
        event.eventId(), event.payloadJson());

// --- 订单栈 ---
DataSource ordersDs = createDataSource("orders");
var ordersEventStore = JdbcEventStores.detect(ordersDs);
var ordersConn = new DataSourceConnectionProvider(ordersDs);
var ordersTx = new ThreadLocalTxContext();

OutboxDispatcher ordersDispatcher = OutboxDispatcher.builder()
    .connectionProvider(ordersConn)
    .eventStore(ordersEventStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("Order", "OrderPlaced", sharedListener)
        .register("Order", "OrderShipped", sharedListener))
    .build();

OutboxPoller ordersPoller = OutboxPoller.builder()
    .connectionProvider(ordersConn)
    .eventStore(ordersEventStore)
    .handler(new DispatcherPollerHandler(ordersDispatcher))
    .skipRecent(Duration.ofMillis(500))
    .batchSize(50)
    .intervalMs(1000)
    .build();
ordersPoller.start();

var ordersTxManager = new JdbcTransactionManager(ordersConn, ordersTx);
var ordersWriter = new OutboxWriter(ordersTx, ordersEventStore,
    new DispatcherCommitHook(ordersDispatcher));

// --- 库存栈（同样的模式，不同数据源）---
DataSource inventoryDs = createDataSource("inventory");
var invEventStore = JdbcEventStores.detect(inventoryDs);
var invConn = new DataSourceConnectionProvider(inventoryDs);
var invTx = new ThreadLocalTxContext();

OutboxDispatcher invDispatcher = OutboxDispatcher.builder()
    .connectionProvider(invConn)
    .eventStore(invEventStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("Inventory", "StockReserved", sharedListener)
        .register("Inventory", "StockDepleted", sharedListener))
    .build();

OutboxPoller invPoller = OutboxPoller.builder()
    .connectionProvider(invConn)
    .eventStore(invEventStore)
    .handler(new DispatcherPollerHandler(invDispatcher))
    .skipRecent(Duration.ofMillis(500))
    .batchSize(50)
    .intervalMs(1000)
    .build();
invPoller.start();

var invTxManager = new JdbcTransactionManager(invConn, invTx);
var invWriter = new OutboxWriter(invTx, invEventStore,
    new DispatcherCommitHook(invDispatcher));

// --- 分别向各自的栈发布事件 ---
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

每个栈完全独立 -- 独立的 Worker 线程、独立的 Poller、独立的事务管理器。唯一共享的是无状态的 Listener。

### 运行多数据源 Demo

```bash
mvn install -DskipTests && mvn -pl samples/outbox-multi-ds-demo exec:java
```
