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
9. [事件清理](#9-事件清理)
10. [分布式追踪（OpenTelemetry）](#10-分布式追踪opentelemetry)
11. [死信事件管理](#11-死信事件管理)
12. [Micrometer 监控](#12-micrometer-监控)
13. [自定义 JsonCodec](#13-自定义-jsoncodec)

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

DataSource dataSource = /* 你的 DataSource */;

var outboxStore = JdbcOutboxStores.detect(dataSource);
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);
ThreadLocalTxContext txContext = new ThreadLocalTxContext();

OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .listenerRegistry(new DefaultListenerRegistry()
        .register("UserCreated", event -> {
          // 发送到 MQ；用 event.eventId() 做去重
        }))
    .interceptor(EventInterceptor.before(event ->
        System.out.println("正在分发: " + event.eventType())))
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

---

## 3. 完整示例（H2 内存库）

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
                System.out.println("已发送到 MQ: " + event.eventId())))
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

通过 `headers(...)` 传入的 Header key 不能为 `null`。

---

## 5. Spring 集成

`outbox-spring-adapter` 模块提供了 `SpringTxContext`，接入 Spring 事务生命周期，使 `afterCommit` 回调在 `@Transactional` 方法提交后自动触发。
`SpringTxContext` 在注册 `afterCommit`/`afterRollback` 回调时要求 Spring transaction synchronization 处于 active 状态。

### Bean 配置

在 `@Configuration` 类中注册所有 outbox 组件：

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
            log.info("用户已创建: id={}, payload={}",
                event.eventId(), event.payloadJson()))
        .register("Order", "OrderPlaced", event ->
            log.info("订单已提交: id={}, payload={}",
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
            log.info("[审计] 正在分发: type={}, aggregateId={}",
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

### 在 @Transactional 方法中发布事件

注入 `OutboxWriter`，在 `@Transactional` 方法中调用 `write()`。事件写入与业务逻辑共享同一个数据库事务，Spring 提交后 `DispatcherWriterHook` 自动触发。

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
    .outboxStore(outboxStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .skipRecent(Duration.ofMillis(1000))
    .batchSize(200)
    .intervalMs(5000)
    .claimLocking("poller-node-1", Duration.ofMinutes(5))
    .build();
```

- 每个 Poller 通过设置 `locked_by`/`locked_at` 列来 claim 事件
- 超过配置的超时时间的锁会被自动回收
- 事件到达 DONE、RETRY 或 DEAD 状态时锁自动释放
- 单节点部署不需要锁，省略 `claimLocking` 即可

---

## 7. CDC 消费（高 QPS 场景）

高吞吐场景下，可以关闭进程内 Poller，改用 CDC 消费 outbox 表。

1. 不启动 `OutboxPoller`
2. 创建 `OutboxWriter` 时不传 Hook（或用 `WriterHook.NOOP`），跳过热路径入队
3. 用 CDC 监听 `outbox_event` 的 INSERT，下游按 `event_id` 去重
4. 纯 CDC 模式下可以不更新状态，将表当作 append-only 使用，配合分区 + TTL 做数据清理

```java
import outbox.OutboxWriter;
import outbox.WriterHook;

OutboxWriter writer = new OutboxWriter(txContext, outboxStore);
// 或: new OutboxWriter(txContext, outboxStore, WriterHook.NOOP)
```

如果同时启用了 `DispatcherWriterHook` 和 CDC，下游需要做去重，或只选其中一条投递路径。

---

## 8. 多数据源

Outbox 模式要求 `outbox_event` 表和业务数据在**同一个数据库**中，保证写入的事务原子性。当系统涉及多个数据库时，每个数据源需要独立的完整 outbox 栈。无状态的 `EventListener` 和 `EventInterceptor` 可以跨栈共享，但每个栈有自己的 `ListenerRegistry`（路由表）。

### 每个栈的组件

每个数据源需要以下全部组件，彼此完全独立：

| 组件 | 用途 |
|-----------|---------|
| `DataSource` | 数据库连接池 |
| `DataSourceConnectionProvider` | 为 outbox 组件封装 DataSource |
| `OutboxStore` | 通过 `JdbcOutboxStores.detect()` 自动识别 |
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

// --- 库存栈（同样的模式，不同数据源）---
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

---

## 9. 事件清理

Outbox 表是临时缓冲区而非 outbox 存储。随着时间推移，终态事件（DONE、DEAD）不断积累，会降低 Poller 查询性能。`OutboxPurgeScheduler` 可定期删除这些过期事件。

### 基本配置

```java
import outbox.purge.OutboxPurgeScheduler;
import outbox.jdbc.purge.H2EventPurger;   // 或 MySqlEventPurger、PostgresEventPurger
import outbox.jdbc.DataSourceConnectionProvider;

import java.time.Duration;

DataSource dataSource = /* 你的 DataSource */;
DataSourceConnectionProvider connectionProvider = new DataSourceConnectionProvider(dataSource);

OutboxPurgeScheduler purgeScheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)
    .purger(new H2EventPurger())       // 根据数据库选择
    .retention(Duration.ofDays(7))     // 删除 7 天前的 DONE/DEAD 事件
    .batchSize(500)                    // 每批行数（控制锁持续时间）
    .intervalSeconds(3600)             // 每小时执行一次
    .build();

purgeScheduler.start();

// ... 应用运行 ...

purgeScheduler.close();  // 优雅关闭
```

调用 `close()` 后不能再次调用 `start()`；否则会抛出 `IllegalStateException`。

### 选择合适的清理器

| 数据库     | 清理器类              |
|------------|----------------------|
| H2         | `H2EventPurger`      |
| MySQL/TiDB | `MySqlEventPurger`   |
| PostgreSQL | `PostgresEventPurger`|

所有清理器类均支持自定义表名：`new H2EventPurger("custom_outbox")`。

### 一次性清理

可以不启动定时器，直接触发单次清理：

```java
OutboxPurgeScheduler purgeScheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)
    .purger(new H2EventPurger())
    .retention(Duration.ofDays(30))
    .build();

purgeScheduler.runOnce();  // 立即清理
purgeScheduler.close();
```

### Spring 集成

在现有 outbox 配置的基础上，将清理调度器注册为 Spring Bean：

```java
@Bean(destroyMethod = "close")
public OutboxPurgeScheduler purgeScheduler(
    DataSourceConnectionProvider connectionProvider,
    AbstractJdbcOutboxStore outboxStore) {
  // 根据数据库选择清理器
  var purger = new MySqlEventPurger();

  OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
      .connectionProvider(connectionProvider)
      .purger(purger)
      .retention(Duration.ofDays(14))
      .batchSize(1000)
      .intervalSeconds(1800)  // 每 30 分钟
      .build();
  scheduler.start();
  return scheduler;
}
```

### 工作原理

1. 每隔 `intervalSeconds` 秒，调度器计算截止时间 `cutoff = now - retention`
2. 删除终态事件（DONE 或 DEAD 状态），条件为 `COALESCE(done_at, created_at) < cutoff`
3. 分批删除，每批使用独立的自动提交连接，批大小为 `batchSize`
4. 持续分批直到某批删除行数小于 `batchSize`（积压清空）
5. 活跃事件（NEW、RETRY）永远不会被触及

### 注意事项

- **先归档**：如需审计记录，应在 `EventListener` 中归档事件，趁它们超过保留期被清理之前
- **无需改表**：清理器使用现有的 `outbox_event` 表，无需新表或新列
- **安全**：仅删除终态事件（DONE=1、DEAD=3），活跃事件（NEW=0、RETRY=2）永远不受影响

---

## 10. 分布式追踪（OpenTelemetry）

Outbox 框架不捆绑 OpenTelemetry 依赖，但现有的 `EventEnvelope.headers` 和 `EventInterceptor` 钩子足以在异步边界传播追踪上下文。

### 写入侧：注入追踪上下文

写入事件时，将当前 Span 的 W3C `traceparent` 头注入到 headers 中：

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

// 将当前追踪上下文捕获到 headers
Map<String, String> headers = new HashMap<>();
GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
    .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));

EventEnvelope envelope = EventEnvelope.builder("OrderPlaced")
    .aggregateType("Order")
    .aggregateId("order-123")
    .headers(headers)  // 包含 traceparent、tracestate
    .payloadJson("{\"item\":\"widget\"}")
    .build();

writer.write(envelope);
```

### 调度侧：提取并关联

注册一个 `EventInterceptor`，从事件 headers 中提取追踪上下文并创建关联 Span：

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

// ThreadLocal 用于在 beforeDispatch 和 afterDispatch 之间传递 Scope
ThreadLocal<Scope> scopeHolder = new ThreadLocal<>();

EventInterceptor tracingInterceptor = new EventInterceptor() {
  @Override
  public void beforeDispatch(OutboxEvent event) {
    // 从 JSON 解析 headers
    Map<String, String> headers = JsonCodec.getDefault().parseObject(event.headersJson());

    // 提取上游追踪上下文
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

    // 启动新 Span 并关联生产者追踪
    Span span = tracer.spanBuilder("outbox.dispatch " + event.eventType())
        .setParent(extracted)
        .setSpanKind(SpanKind.CONSUMER)
        .setAttribute("outbox.event_id", event.eventId())
        .setAttribute("outbox.event_type", event.eventType())
        .setAttribute("outbox.aggregate_type", event.aggregateType())
        .startSpan();

    // makeCurrent() 返回的 Scope 必须关闭以恢复之前的上下文
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

### 接线

在 dispatcher builder 上**最先**注册追踪拦截器，使其包裹所有其他拦截器：

```java
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .listenerRegistry(registry)
    .interceptor(tracingInterceptor)          // 追踪优先
    .interceptor(EventInterceptor.before(e -> // 然后日志、审计等
        log.info("Dispatching: {}", e.eventType())))
    .build();
```

这样无需框架级 OTel 依赖，即可实现从写入到异步调度的端到端追踪。

---

## 11. 死信事件管理

超过 `maxAttempts` 或没有注册 Listener 的事件最终进入 DEAD 状态。`DeadEventManager` 提供了一个管理连接生命周期的门面，用于查询、重放和统计这些事件。

### 基本配置

```java
import outbox.dead.DeadEventManager;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.JdbcOutboxStores;

DataSource dataSource = /* 你的 DataSource */;
var connectionProvider = new DataSourceConnectionProvider(dataSource);
var outboxStore = JdbcOutboxStores.detect(dataSource);

DeadEventManager deadEvents = new DeadEventManager(connectionProvider, outboxStore);
```

### 查询死信事件

```java
// 查询所有死信事件（最多 100 条）
var all = deadEvents.query(null, null, 100);

// 按事件类型过滤
var userErrors = deadEvents.query("UserCreated", null, 50);

// 同时按事件类型和聚合类型过滤
var orderErrors = deadEvents.query("OrderPlaced", "Order", 50);
```

### 重放单个事件

```java
boolean replayed = deadEvents.replay("01HXYZ...");  // 事件 ID
if (replayed) {
  System.out.println("事件已重置为 NEW 状态，将被重新处理");
}
```

### 批量重放

```java
// 分批重放所有 "UserCreated" 死信事件，每批 100 条
int total = deadEvents.replayAll("UserCreated", null, 100);
System.out.println("已重放 " + total + " 个事件");

// 重放所有死信事件
int allReplayed = deadEvents.replayAll(null, null, 100);
```

### 统计死信事件

```java
int total = deadEvents.count(null);              // 所有死信事件
int userCount = deadEvents.count("UserCreated"); // 按事件类型统计
```

### Spring 集成

```java
@Bean
public DeadEventManager deadEventManager(
    DataSourceConnectionProvider connectionProvider,
    AbstractJdbcOutboxStore outboxStore) {
  return new DeadEventManager(connectionProvider, outboxStore);
}
```

所有方法内部处理 `SQLException` — 以 SEVERE 级别记录日志并返回安全默认值（`List.of()`、`false`、`0`），不会向外抛异常。

---

## 12. Micrometer 监控

`outbox-micrometer` 模块提供了 `MicrometerMetricsExporter`，向 Micrometer `MeterRegistry` 注册计数器和仪表盘，支持导出到 Prometheus、Grafana、Datadog 等。

### 添加依赖

```xml
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-micrometer</artifactId>
  <version>0.6.0</version>
</dependency>
```

### 接入 Dispatcher

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
    .metrics(metrics)  // 接入监控
    .build();

OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(new DispatcherPollerHandler(dispatcher))
    .metrics(metrics)  // Poller 使用同一个 exporter
    .build();
```

### Spring Boot 自动配置

在 Spring Boot 中使用 `spring-boot-starter-actuator` 时，`MeterRegistry` 会被自动配置：

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

配置完成后，指标可通过 `/actuator/prometheus` 端点获取。

### 指标名称

**计数器：**

| 名称 | 说明 |
|------|------|
| `outbox.enqueue.hot` | 通过热路径入队的事件 |
| `outbox.enqueue.hot.dropped` | 被丢弃的事件（热队列满） |
| `outbox.enqueue.cold` | 通过冷路径（Poller）入队的事件 |
| `outbox.dispatch.success` | 成功分发的事件 |
| `outbox.dispatch.failure` | 失败的事件（将重试） |
| `outbox.dispatch.dead` | 进入 DEAD 状态的事件 |

**仪表盘：**

| 名称 | 说明 |
|------|------|
| `outbox.queue.hot.depth` | 当前热队列深度 |
| `outbox.queue.cold.depth` | 当前冷队列深度 |
| `outbox.lag.oldest.ms` | 最旧待处理事件的延迟（毫秒） |

### 多实例前缀

运行多个 outbox 栈时，使用自定义前缀避免指标冲突：

```java
// 订单栈
var ordersMetrics = new MicrometerMetricsExporter(registry, "orders.outbox");

// 库存栈
var inventoryMetrics = new MicrometerMetricsExporter(registry, "inventory.outbox");
```

这会生成 `orders.outbox.dispatch.success` 和 `inventory.outbox.dispatch.success` 等指标。

---

## 13. 自定义 JsonCodec

框架使用 `JsonCodec` 对事件 header map（`Map<String, String>`）进行 JSON 编解码。内置的 `DefaultJsonCodec` 是一个轻量级、零依赖的实现。如果项目已有 Jackson 或 Gson，可以替换它以获得更好的性能或兼容性。

### 何时替换 DefaultJsonCodec

- 希望使用已有的 Jackson/Gson `ObjectMapper` 保持一致性
- 需要对超大 header map 有更好的性能
- 希望利用 Jackson 的流式解析器

### 实现接口

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
      throw new IllegalArgumentException("编码 headers 失败", e);
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
      throw new IllegalArgumentException("解析 headers JSON 失败", e);
    }
  }
}
```

### 注入到组件

自定义 codec 可以在三个位置注入：

```java
JsonCodec codec = new JacksonJsonCodec();

// 1. OutboxStore（用于 insertNew 和 poll/claim 行映射）
var outboxStore = new H2OutboxStore("outbox_event", codec);

// 2. OutboxPoller（用于 OutboxEvent → EventEnvelope 转换）
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)
    .outboxStore(outboxStore)
    .handler(handler)
    .jsonCodec(codec)
    .build();

// 3. 自动探测时传入自定义 codec
var autoStore = JdbcOutboxStores.detect(dataSource, codec);
```

如不注入 codec，所有组件默认使用 `JsonCodec.getDefault()`（内置的 `DefaultJsonCodec` 单例）。
