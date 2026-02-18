[English](SPEC.md) | [中文](SPEC.zh-CN.md)

# Outbox 框架技术规范

API 契约、数据模型、行为规则、配置与可观测性的完整技术规范。

项目目标与验收标准见 [OBJECTIVE.md](OBJECTIVE.zh-CN.md)。
教程与代码示例见 [TUTORIAL.md](TUTORIAL.zh-CN.md)。

## 目录

1. [架构概览](#1-架构概览)
2. [模块划分](#2-模块划分)
3. [核心抽象](#3-核心抽象)
4. [数据模型](#4-数据模型)
5. [Event Envelope](#5-event-envelope)
6. [类型安全的 EventType 与 AggregateType](#6-类型安全的-eventtype-与-aggregatetype)
7. [公开 API](#7-公开-api)
8. [JDBC Outbox Store](#8-jdbc-outbox-store)
9. [OutboxDispatcher](#9-outboxdispatcher)
10. [OutboxPoller](#10-outboxpoller)
11. [注册中心](#11-注册中心)
12. [重试策略](#12-重试策略)
13. [背压与降级](#13-背压与降级)
14. [配置](#14-配置)
15. [可观测性](#15-可观测性)
16. [线程安全](#16-线程安全)
17. [事件清理](#17-事件清理)
18. [死信事件管理](#18-死信事件管理)

---

## 1. 架构概览

### 1.1 核心组件

| 组件 | 职责 |
|------|------|
| **OutboxWriter** | 业务代码在事务中调用的写入 API |
| **TxContext** | 事务生命周期抽象（afterCommit / afterRollback） |
| **OutboxStore** | 通过 `java.sql.Connection` 完成事件的增删改查 |
| **OutboxDispatcher** | 热/冷双队列 + Worker 线程池，执行 Listener 并更新状态 |
| **ListenerRegistry** | 事件类型到 Listener 的映射 |
| **OutboxPoller** | 低频扫表兜底，将未处理事件交给 Handler |
| **OutboxPurgeScheduler** | 定期清理超过保留期的终态事件（DONE/DEAD） |
| **InFlightTracker** | 内存级去重，防止同一事件并发处理 |

### 1.2 事件流转

```text
+--------------------------------------------------------------+
|                       事务作用域                                |
|                                                               |
|  业务代码 --> OutboxWriter.write()                              |
|                    |                                          |
|                    +--> OutboxStore.insertNew() --> [DB]       |
|                    |                                          |
|                    +--> TxContext.afterCommit(hook)            |
+--------------------+----------------------------+-------------+
                     |                            |
              提交后回调 (afterCommit)           轮询待处理 (poll)
                     |                            |
                     v                            v
                  热路径                         冷路径
                     |                            |
                     v                            v
      WriterHook.afterCommit()       OutboxPoller.poll()
      Dispatcher.enqueueHot()         pollPending()/claimPending()
                     |                Handler.handle()
                     |                Dispatcher.enqueueCold()
                     |                            |
                     +----------+--+--------------+
                                |
                                v
                   OutboxDispatcher.process()
                     -> ListenerRegistry.listenerFor()
                     -> EventListener.onEvent()
                     -> markDone/Retry/Dead()
```

### 1.3 队列优先级

OutboxDispatcher 的优先级策略：
- **热队列**：来自业务线程的 afterCommit 入队（优先）
- **冷队列**：Poller / Handler 兜底入队

---

## 2. 模块划分

### 2.1 outbox-core

核心接口、Hook、Dispatcher、Poller 和注册中心。**零外部依赖。**

包结构：
- `outbox` — 主 API：OutboxWriter、EventEnvelope、EventType、AggregateType、EventListener、WriterHook
- `outbox.spi` — 扩展点接口：TxContext、ConnectionProvider、OutboxStore、EventPurger、MetricsExporter
- `outbox.model` — 领域对象：OutboxEvent、EventStatus
- `outbox.dispatch` — OutboxDispatcher、重试策略、InFlight 追踪
- `outbox.poller` — OutboxPoller、OutboxPollerHandler
- `outbox.registry` — Listener 注册中心
- `outbox.purge` — OutboxPurgeScheduler（定时清理终态事件）
- `outbox.dead` — DeadEventManager（死信事件查询/重放的连接管理门面）
- `outbox.util` — JsonCodec（接口）、DefaultJsonCodec（内置零依赖实现）

### 2.2 outbox-jdbc

JDBC OutboxStore 继承体系、EventPurger 继承体系与手动事务管理工具。

包结构：
- `outbox.jdbc` — 共享工具：JdbcTemplate、OutboxStoreException、DataSourceConnectionProvider
- `outbox.jdbc.store` — OutboxStore 继承体系（通过 ServiceLoader 注册）
- `outbox.jdbc.purge` — EventPurger 继承体系
- `outbox.jdbc.tx` — 事务管理

按包分类：

**`outbox.jdbc.store`**
- `AbstractJdbcOutboxStore` — 基类，共享 SQL、行映射器，默认使用 H2 兼容的 claim 实现
- `H2OutboxStore` — H2（继承默认的子查询式 claim）
- `MySqlOutboxStore` — MySQL / TiDB（`UPDATE...ORDER BY...LIMIT` claim）
- `PostgresOutboxStore` — PostgreSQL（`FOR UPDATE SKIP LOCKED` + `RETURNING` claim）
- `JdbcOutboxStores` — ServiceLoader 注册表 + `detect(DataSource)` 自动探测

**`outbox.jdbc.purge`**
- `AbstractJdbcEventPurger` — 基类清理器，默认使用子查询式 DELETE（H2/PostgreSQL）
- `H2EventPurger` — H2（继承默认实现）
- `MySqlEventPurger` — MySQL / TiDB（`DELETE...ORDER BY...LIMIT`）
- `PostgresEventPurger` — PostgreSQL（继承默认实现）

**`outbox.jdbc.tx`**
- `ThreadLocalTxContext` — 基于 ThreadLocal 的 TxContext，用于手动事务管理
- `JdbcTransactionManager` — 手动 JDBC 事务辅助类

**`outbox.jdbc`**（根包）
- `JdbcTemplate` — 轻量级 JDBC 辅助类（update、query、updateReturning）
- `OutboxStoreException` — JDBC 层异常
- `DataSourceConnectionProvider` — 从 DataSource 获取连接的 ConnectionProvider

### 2.3 outbox-spring-adapter

可选的 Spring 集成模块。

类：
- `SpringTxContext` — 基于 Spring TransactionSynchronizationManager 实现 TxContext

### 2.4 outbox-micrometer

Micrometer 监控桥接模块，支持 Prometheus、Grafana、Datadog 等监控后端。

类：
- `MicrometerMetricsExporter` — 基于 Micrometer `MeterRegistry` 实现 `MetricsExporter`

### 2.5 samples/outbox-demo

独立 H2 示例（无 Spring）。

### 2.6 samples/outbox-spring-demo

Spring Boot REST API 示例。

### 2.7 samples/outbox-multi-ds-demo

多数据源示例（两个 H2 数据库）。

---

## 3. 核心抽象

### 3.1 TxContext

```java
public interface TxContext {
  boolean isTransactionActive();
  Connection currentConnection();
  void afterCommit(Runnable callback);
  void afterRollback(Runnable callback);
}
```

规则：
- `currentConnection()` 必须返回业务操作所用的同一连接。
- `afterCommit()` 回调仅在事务成功提交后执行。
- 注册 `afterCommit()`/`afterRollback()` 时，必须处于 transaction synchronization active 状态。
- 若 `isTransactionActive()` 为 `false` 时调用 `write()`，必须立即抛异常（fail-fast）。

### 3.2 ConnectionProvider

```java
public interface ConnectionProvider {
  Connection getConnection() throws SQLException;
}
```

供 OutboxDispatcher 和 OutboxPoller 在业务事务外获取短连接。

### 3.3 实现列表

| 实现 | 所属模块 | 说明 |
|------|----------|------|
| `ThreadLocalTxContext` | outbox-jdbc | 手动 JDBC 事务管理 |
| `SpringTxContext` | outbox-spring-adapter | Spring @Transactional 集成 |

### 3.4 JsonCodec

```java
public interface JsonCodec {
  static JsonCodec getDefault() { ... }

  String toJson(Map<String, String> headers);
  Map<String, String> parseObject(String json);
}
```

- `getDefault()` 返回 `DefaultJsonCodec` 单例 — 轻量级、零依赖的编解码器，仅支持扁平 `Map<String, String>` 对象。
- `toJson()` 对 null 或空 Map 返回 `null`；key 为 null 时抛 `IllegalArgumentException`。
- `parseObject()` 对 `null`、空字符串或 `"null"` 输入返回空 Map。
- 已有 Jackson 或 Gson 的项目可实现该接口并注入到：
  - `AbstractJdbcOutboxStore` 构造函数：`new H2OutboxStore(tableName, codec)`
  - `OutboxPoller.Builder.jsonCodec(codec)`
  - `JdbcOutboxStores.detect(dataSource, codec)`

---

## 4. 数据模型

### 4.1 表：outbox_event

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

### 4.2 状态值

| 值 | 名称 | 说明 |
|----|------|------|
| 0 | NEW | 刚插入，等待处理 |
| 1 | DONE | 处理成功 |
| 2 | RETRY | 处理失败，等待重试 |
| 3 | DEAD | 超出最大重试次数，进入死信 |

---

## 5. Event Envelope

### 5.1 字段

| 字段 | 类型 | 必填 | 默认值 |
|------|------|------|--------|
| eventId | String | 否 | ULID（单调递增） |
| eventType | String | 是 | - |
| occurredAt | Instant | 否 | Instant.now() |
| aggregateType | String | 否 | AggregateType.GLOBAL.name()（`"__GLOBAL__"`） |
| aggregateId | String | 否 | null |
| tenantId | String | 否 | null |
| headers | Map<String,String> | 否 | 空 Map |
| payloadJson | String | 二选一* | - |
| payloadBytes | byte[] | 二选一* | - |

*payloadJson 和 payloadBytes 必须设置其一，不能同时设置。

### 5.2 约束

- Payload 最大 **1MB**（1,048,576 字节）
- Payload 序列化一次，DB 写入和投递共用同一份
- EventEnvelope 不可变（bytes 和 headers 均做防御性拷贝）
- `headers` 不能包含 `null` key。

### 5.3 Builder 模式

```java
// 类型安全的 EventType
EventEnvelope envelope = EventEnvelope.builder(UserEvents.USER_CREATED)
    .aggregateType(Aggregates.USER)
    .aggregateId("123")
    .payloadJson("{\"name\":\"John\"}")
    .build();

// 字符串方式
EventEnvelope envelope = EventEnvelope.builder("UserCreated")
    .payloadJson("{}")
    .build();

// 简写
EventEnvelope envelope = EventEnvelope.ofJson("UserCreated", "{}");
```

### 5.4 多租户支持

`tenantId` 字段为多租户应用提供**透传元数据**：

```java
EventEnvelope envelope = EventEnvelope.builder("OrderCreated")
    .tenantId("tenant-123")
    .aggregateId("order-456")
    .payloadJson("{...}")
    .build();
```

**框架行为：**
- `tenantId` 持久化到 `outbox_event` 表
- 轮询和投递时携带 `tenantId`
- Listener 通过 `event.tenantId()` 获取

**框架不负责：**
- 按租户过滤轮询
- 租户隔离或分区
- 按租户独立配置

**应用层职责：**
- 发布事件时设置 `tenantId`
- 在 Listener 中根据 `tenantId` 进行路由或租户特定逻辑
- 如需租户隔离，在数据库层自行实现（如行级安全策略、独立 Schema）

---

## 6. 类型安全的 EventType 与 AggregateType

### 6.1 EventType 接口

```java
public interface EventType {
  String name();
}
```

### 6.2 枚举实现

```java
public enum UserEvents implements EventType {
  USER_CREATED,
  USER_UPDATED,
  USER_DELETED;
}

// 用法
EventEnvelope.builder(UserEvents.USER_CREATED)
    .payloadJson("{}")
    .build();
```

### 6.3 动态实现

```java
EventType type = StringEventType.of("DynamicEvent");

EventEnvelope.builder(type)
    .payloadJson("{}")
    .build();
```

### 6.4 AggregateType 接口

```java
public interface AggregateType {
  AggregateType GLOBAL = ...; // name() 返回 "__GLOBAL__"

  String name();
}
```

`AggregateType.GLOBAL` 是 EventEnvelope 未显式指定 aggregateType 时的默认值。

### 6.5 AggregateType 用法

```java
// 枚举方式
public enum Aggregates implements AggregateType {
  USER, ORDER, PRODUCT
}

EventEnvelope.builder(eventType)
    .aggregateType(Aggregates.USER)
    .aggregateId("user-123")
    .build();

// 动态方式
EventEnvelope.builder(eventType)
    .aggregateType(StringAggregateType.of("CustomAggregate"))
    .aggregateId("id-456")
    .build();
```

---

## 7. 公开 API

### 7.1 OutboxWriter

```java
public final class OutboxWriter {
  public OutboxWriter(TxContext txContext, OutboxStore outboxStore);
  public OutboxWriter(TxContext txContext, OutboxStore outboxStore, WriterHook writerHook);

  public String write(EventEnvelope event);              // 被抑制时返回 null
  public String write(String eventType, String payloadJson);  // 被抑制时返回 null
  public String write(EventType eventType, String payloadJson); // 被抑制时返回 null
  public List<String> writeAll(List<EventEnvelope> events);  // 被抑制时返回空列表
}
```

语义：
- 必须在活跃事务中调用（通过 TxContext 校验）
- `write()` 委托给 `writeAll()`（单元素列表）
- `writeAll()` 调用 `WriterHook.beforeWrite()`，可变换或抑制事件列表
- 若 `beforeWrite` 返回 null 或空列表，不执行插入（写入被抑制）
- 使用 `TxContext.currentConnection()` 在当前事务内插入 outbox 行（状态 NEW）
- 每次 `writeAll` 批次只注册一个 `afterCommit`/`afterRollback` 回调
- `afterWrite`/`afterCommit`/`afterRollback` 中 Hook 抛异常时不得传播（仅记录日志）
- 未提供 Hook（或使用 `WriterHook.NOOP`）时不执行任何提交后动作（由 Poller / CDC 负责后续投递）

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

生命周期：`beforeWrite`（变换/抑制）→ 插入 → `afterWrite` → 事务提交/回滚 → `afterCommit`/`afterRollback`。

- `beforeWrite` 可返回修改后的列表；返回 null 或空列表将抑制写入
- `afterWrite`/`afterCommit`/`afterRollback` 异常被吞掉并记录日志
- `DispatcherWriterHook` 实现 `afterCommit`，将每个事件入 Dispatcher 热队列

---

## 8. JDBC Outbox Store

### 8.1 接口

```java
public interface OutboxStore {
  void insertNew(Connection conn, EventEnvelope event);
  int markDone(Connection conn, String eventId);
  int markRetry(Connection conn, String eventId, Instant nextAt, String error);
  int markDead(Connection conn, String eventId, String error);
  List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit);

  // 基于 claim 的锁定（默认回退到 pollPending）
  default List<OutboxEvent> claimPending(
      Connection conn, String ownerId, Instant now,
      Instant lockExpiry, Duration skipRecent, int limit);
}
```

### 8.2 SQL 语义

**插入：**
```sql
INSERT INTO outbox_event (event_id, event_type, aggregate_type, aggregate_id,
  tenant_id, payload, headers, status, attempts, available_at, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
```

**标记完成（幂等）：**
```sql
UPDATE outbox_event
SET status = 1, done_at = ?, locked_by = NULL, locked_at = NULL
WHERE event_id = ? AND status <> 1
```

**标记重试：**
```sql
UPDATE outbox_event
SET status = 2, attempts = attempts + 1, available_at = ?, last_error = ?,
    locked_by = NULL, locked_at = NULL
WHERE event_id = ? AND status <> 1
```

**标记死信：**
```sql
UPDATE outbox_event
SET status = 3, last_error = ?, locked_by = NULL, locked_at = NULL
WHERE event_id = ? AND status <> 1
```

**轮询待处理事件：**
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

### 8.3 规则

- 必须使用 PreparedStatement 绑定参数
- 不得关闭事务内的连接（生命周期由调用方管理）
- 错误信息截断至 4000 字符

---

## 9. OutboxDispatcher

### 9.1 Builder

```java
OutboxDispatcher dispatcher = OutboxDispatcher.builder()
    .connectionProvider(connectionProvider)  // 必填
    .outboxStore(outboxStore)                  // 必填
    .listenerRegistry(listenerRegistry)      // 必填
    .inFlightTracker(tracker)                // 默认: DefaultInFlightTracker
    .retryPolicy(policy)                     // 默认: ExponentialBackoffRetryPolicy(200, 60_000)
    .maxAttempts(10)                         // 默认: 10
    .workerCount(4)                          // 默认: 4
    .hotQueueCapacity(1000)                  // 默认: 1000
    .coldQueueCapacity(1000)                 // 默认: 1000
    .metrics(metricsExporter)                // 默认: MetricsExporter.NOOP
    .interceptor(interceptor)                // 可选，可多次调用
    .interceptors(List.of(i1, i2))           // 可选，批量添加
    .drainTimeoutMs(5000)                    // 默认: 5000
    .build();
```

### 9.2 方法

```java
boolean enqueueHot(QueuedEvent event)  // 队列满或正在关闭时返回 false
boolean enqueueCold(QueuedEvent event) // 队列满或正在关闭时返回 false
int coldQueueRemainingCapacity()       // 冷队列剩余容量
void close()                           // 优雅关闭，等待排空
```

### 9.3 处理流程

每个队列事件的处理步骤：
1. **去重**：eventId 已在处理中则丢弃
2. **Interceptor**：按注册顺序执行 `beforeDispatch`
3. **路由**：通过 `listenerRegistry.listenerFor(aggregateType, eventType)` 查找唯一 Listener
4. **无路由**：找不到 Listener 则抛 `UnroutableEventException`，直接标记 DEAD（不重试）
5. **执行**：调用 Listener
6. **后置处理**：逆序执行 `afterDispatch`（成功时 error 为 null，失败时为异常对象）
7. **成功**：更新 DB 为 DONE，从 InFlight 移除
8. **失败**：按退避策略更新为 RETRY，超过 maxAttempts 则标记 DEAD；从 InFlight 移除

### 9.4 同步执行模型

Worker 在自身线程上**同步**执行 Listener：

```
Worker 线程:
  loop:
    event = pollFairly()      // 2:1 热:冷 加权轮询
    interceptors.beforeDispatch(event)
    listener = registry.listenerFor(aggregateType, eventType)
    listener.onEvent(event)   // 阻塞执行
    interceptors.afterDispatch(event, null)
    markDone(event)
```

### 9.5 EventInterceptor

用于审计、日志、指标等横切关注点：

```java
public interface EventInterceptor {
  default void beforeDispatch(EventEnvelope event) throws Exception {}
  default void afterDispatch(EventEnvelope event, Exception error) {}
}
```

- `beforeDispatch` 按注册顺序执行；抛异常则短路进入 RETRY / DEAD
- `afterDispatch` 逆序执行；抛异常仅记录日志，不向上传播
- 工厂方法：`EventInterceptor.before(hook)`、`EventInterceptor.after(hook)`

### 9.6 公平队列排空

Worker 以 2:1 加权轮询消费队列：热队列占 2/3 的拉取机会，冷队列占 1/3。避免持续高热负载下冷队列饿死。

### 9.7 优雅关闭

`close()` 停止接收新事件，通知 Worker 排空剩余事件，最多等待 `drainTimeoutMs` 毫秒后强制关闭。

这是**天然背压**的核心机制：
- `workerCount` = 最大并发处理数
- Listener 慢 -> Worker 忙碌 -> 无法继续拉取
- 队列满 -> `enqueueHot()` 返回 false -> 优雅降级
- 不会压垮下游系统（MQ、数据库、外部 API）

**调优建议：** 调整 `workerCount` 控制最大并行度。值越大吞吐越高，但也可能压垮下游。

### 9.8 队列元素

```java
public class QueuedEvent {
  EventEnvelope envelope;
  Source source;        // HOT 或 COLD
  int attempts;
}
```

### 9.9 InFlightTracker

防止同一事件被并发处理。

```java
public interface InFlightTracker {
  boolean tryAcquire(String eventId);  // 已在处理中则返回 false
  void release(String eventId);         // 移除追踪
}
```

**DefaultInFlightTracker**：
```java
new DefaultInFlightTracker()           // 无 TTL
new DefaultInFlightTracker(long ttlMs) // 带 TTL，用于回收卡住的条目
```

- 基于 ConcurrentHashMap，线程安全
- TTL 机制用于恢复卡死条目（如 Worker 崩溃场景）

---

## 10. OutboxPoller

### 10.1 Builder

```java
OutboxPoller poller = OutboxPoller.builder()
    .connectionProvider(connectionProvider)  // 必填
    .outboxStore(outboxStore)                  // 必填
    .handler(handler)                        // 必填
    .skipRecent(Duration.ofSeconds(1))       // 默认: Duration.ZERO
    .batchSize(50)                           // 默认: 50
    .intervalMs(5000)                        // 默认: 5000
    .metrics(metricsExporter)                // 默认: MetricsExporter.NOOP
    .claimLocking("poller-1", Duration.ofMinutes(5))  // 可选: 启用多节点 claim 锁定
    .build();
```

### 10.2 方法

```java
void start()    // 启动定时轮询
void poll()     // 执行单次轮询
void close()    // 停止轮询
```

### 10.3 行为

- 按固定间隔运行（默认 5000ms）
- 轮询前检查 Handler 容量，无空间则跳过本轮
- 跳过 `skipRecent` 时间内创建的事件（默认 `Duration.ZERO`）
- 查询 status IN (0, 2) 且 available_at <= now
- 将 OutboxEvent 转为 EventEnvelope
- 交给 Handler 处理（受背压控制）
- 解码失败的事件直接标记 DEAD

### 10.4 事件锁定

配置 `claimLocking` 后，Poller 使用 claim 锁定机制：

- **加锁**：原子设置 `locked_by` 和 `locked_at`
- **过期**：超过配置的超时时间的锁视为已过期，可被重新 claim
- **释放**：`markDone` / `markRetry` / `markDead` 清除 `locked_by` 和 `locked_at`
- **数据库差异**：PostgreSQL 用 `FOR UPDATE SKIP LOCKED` + `RETURNING`；MySQL 用 `UPDATE...ORDER BY...LIMIT`；H2 用子查询式两阶段 claim

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

- 每个解码成功的事件调用一次
- 返回 false 中止当前轮询轮次（背压信号）

### 10.6 CDC 替代方案（可选）

高 QPS 场景下，CDC 可替代进程内 Poller 和热路径 Hook：

- 构造 `OutboxWriter` 时不传 Hook（或传 `WriterHook.NOOP`）
- 不启动 `OutboxPoller`
- CDC 消费者负责下游投递；纯 CDC 模式下状态更新是可选的
- 若不标记 DONE，将表视为 append-only，通过分区 + TTL 做数据保留
- 下游按 `event_id` 去重

---

## 11. 注册中心

### 11.1 EventListener 接口

```java
/**
 * 响应 Outbox 事件的 Listener。
 *
 * 每个 (aggregateType, eventType) 对应唯一一个 Listener。
 * 横切关注点（审计、日志）请使用 EventInterceptor。
 */
public interface EventListener {
  void onEvent(EventEnvelope event) throws Exception;
}
```

### 11.2 ListenerRegistry 接口

```java
public interface ListenerRegistry {
  EventListener listenerFor(String aggregateType, String eventType);
}
```

返回指定 `(aggregateType, eventType)` 的唯一 Listener；未注册则返回 `null`。

### 11.3 DefaultListenerRegistry

```java
// GLOBAL 聚合类型（便捷写法）
registry.register("UserCreated", event -> { ... });

// 指定聚合类型
registry.register("Order", "OrderPlaced", event -> { ... });

// 类型安全的注册
registry.register(Aggregates.USER, UserEvents.USER_CREATED, event -> { ... });
```

- 同一 `(aggregateType, eventType)` 重复注册抛 `IllegalStateException`
- `register(eventType, listener)` 快捷方式默认使用 `AggregateType.GLOBAL`

### 11.4 路由规则

1. 用 `aggregateType + ":" + eventType` 作为 key 查找 Listener
2. 找到则执行该 Listener
3. 找不到则抛 `UnroutableEventException`，事件直接标记 DEAD（不重试）
4. 横切行为（审计/日志）通过 Dispatcher Builder 的 `EventInterceptor` 实现

---

## 12. 重试策略

### 12.1 接口

```java
public interface RetryPolicy {
  long computeDelayMs(int attempts);
}
```

### 12.2 ExponentialBackoffRetryPolicy

```java
public ExponentialBackoffRetryPolicy(long baseDelayMs, long maxDelayMs)
```

公式：
```
delay = min(maxDelay, baseDelay * 2^(attempts-1)) * jitter
jitter = random(0.5, 1.5)
```

### 12.3 默认值

| 参数 | 默认值 |
|------|--------|
| baseDelayMs | 200 |
| maxDelayMs | 60000 |
| maxAttempts | 10 |

---

## 13. 背压与降级

框架通过多级背压机制保护下游系统。

### 13.1 背压模型

```
+---------------------------------------------------------------------------+
|                           背压流转                                         |
+---------------------------------------------------------------------------+
|                                                                            |
|  [Listener 处理慢]                                                         |
|          |                                                                 |
|          v                                                                 |
|  [Worker 阻塞] --> 同一时刻最多处理 N 个事件                                  |
|          |         (N = workerCount)                                        |
|          v                                                                 |
|  [队列填满] --> 有界容量，防止内存无限增长                                      |
|          |                                                                 |
|          v                                                                 |
|  [enqueueHot() 返回 false]                                                 |
|          |                                                                 |
|          v                                                                 |
|  [事件留在 DB] --> Poller / CDC 在容量恢复后捡起                               |
|                                                                            |
+---------------------------------------------------------------------------+
```

### 13.2 有界队列

- 热队列和冷队列必须有界（`ArrayBlockingQueue`）
- 禁止无界队列
- 默认容量各 1000

### 13.3 同步执行的 Worker

Worker 同步（阻塞）执行 Listener，天然实现限流：

| 场景 | 效果 |
|------|------|
| Listener 快 | Worker 迅速回到拉取，高吞吐 |
| Listener 慢 | Worker 被阻塞，队列逐渐填满，自动节流 |
| 下游宕机 | 全部 Worker 阻塞，队列满，事件安全留存 DB |

**关键洞察：** 内存队列满时，数据库充当持久化缓冲区。

### 13.4 热队列满时的行为（DispatcherWriterHook）

- `write()` 不得抛异常
- DispatcherWriterHook 记录 WARNING 日志并递增指标
- 事件以 NEW 状态留在 DB
- Poller 或 CDC 在 Worker 有空闲后接管

### 13.5 冷队列满时的行为

- Poller 停止当前轮次的入队
- 事件留在 DB，下一轮轮询时重试
- 不会丢数据

---

## 14. 配置

配置直接内嵌在 `OutboxDispatcher.Builder` 和 `OutboxPoller` 构造参数中，没有独立的配置对象。

### 14.1 Dispatcher 默认值

| 参数 | 默认值 |
|------|--------|
| workerCount | 4 |
| hotQueueCapacity | 1000 |
| coldQueueCapacity | 1000 |
| maxAttempts | 10 |
| retryPolicy | ExponentialBackoffRetryPolicy(200, 60_000) |
| drainTimeoutMs | 5000 |
| metrics | MetricsExporter.NOOP |

### 14.2 Builder 示例

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

## 15. 可观测性

### 15.1 MetricsExporter 接口

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

`outbox-micrometer` 模块提供了 `MicrometerMetricsExporter`，开箱即用，向 Micrometer `MeterRegistry` 注册计数器和仪表盘。

**构造函数：**

```java
new MicrometerMetricsExporter(MeterRegistry registry)                // 默认前缀: "outbox"
new MicrometerMetricsExporter(MeterRegistry registry, String namePrefix) // 自定义前缀
```

**计数器（单调递增）：**

| 指标名称 | 说明 |
|----------|------|
| `{prefix}.enqueue.hot` | 通过热路径入队的事件 |
| `{prefix}.enqueue.hot.dropped` | 被丢弃的事件（热队列满） |
| `{prefix}.enqueue.cold` | 通过冷路径（Poller）入队的事件 |
| `{prefix}.dispatch.success` | 成功分发的事件 |
| `{prefix}.dispatch.failure` | 失败的事件（将重试） |
| `{prefix}.dispatch.dead` | 进入 DEAD 状态的事件 |

**仪表盘（当前值）：**

| 指标名称 | 说明 |
|----------|------|
| `{prefix}.queue.hot.depth` | 当前热队列深度 |
| `{prefix}.queue.cold.depth` | 当前冷队列深度 |
| `{prefix}.lag.oldest.ms` | 最旧待处理事件的延迟（毫秒） |

默认前缀为 `outbox`。多实例部署时，使用自定义前缀（如 `"orders.outbox"`）避免指标冲突。

### 15.3 日志级别

| 级别 | 事件 |
|------|------|
| WARNING | 热队列丢弃（DispatcherWriterHook） |
| ERROR | 进入 DEAD 状态 |
| ERROR | Dispatcher / Poller 循环异常 |
| SEVERE | 解码失败（如 headers 格式异常） |

热队列丢弃告警由 DispatcherWriterHook 发出。未安装 Hook（纯 CDC 模式）时不会产生告警或指标。

### 15.4 幂等性要求

- 向 MQ 投递的 Listener 必须在消息头/消息体中包含 eventId
- 下游系统按 eventId 去重
- 框架提供 at-least-once 投递保证

---

## 16. 线程安全

| 组件 | 策略 |
|------|------|
| OutboxDispatcher | Worker 线程池（ExecutorService）+ 有界 BlockingQueue |
| 注册中心 | ConcurrentHashMap |
| InFlightTracker | ConcurrentHashMap + CAS 操作 |
| OutboxPoller | 单线程 ScheduledExecutorService |
| OutboxPurgeScheduler | 单线程 ScheduledExecutorService |
| ThreadLocalTxContext | ThreadLocal 存储 |

---

## 17. 事件清理

### 17.1 概述

Outbox 表是临时缓冲区而非 outbox 存储。终态事件（DONE 和 DEAD）应在保留期后被清理，以防表膨胀并维持 Poller 查询性能。如需归档事件用于审计，应在 `EventListener` 中完成。

### 17.2 EventPurger 接口

```java
public interface EventPurger {
  int purge(Connection conn, Instant before, int limit);
}
```

- 删除终态事件（DONE + DEAD），条件为 `COALESCE(done_at, created_at) < before`
- 接收显式 `Connection`（调用方控制事务），与 `OutboxStore` 模式一致
- 返回实际删除的行数
- `limit` 限制单次批量大小，控制锁持续时间

### 17.3 JDBC 清理器继承体系

| 类 | 数据库 | 策略 |
|----|--------|------|
| `AbstractJdbcEventPurger` | 基类 | 子查询式 DELETE（默认） |
| `H2EventPurger` | H2 | 继承默认 |
| `MySqlEventPurger` | MySQL/TiDB | `DELETE...ORDER BY...LIMIT` |
| `PostgresEventPurger` | PostgreSQL | 继承默认 |

**默认清理 SQL（H2、PostgreSQL）：**
```sql
DELETE FROM outbox_event WHERE event_id IN (
  SELECT event_id FROM outbox_event
  WHERE status IN (1, 3) AND COALESCE(done_at, created_at) < ?
  ORDER BY created_at LIMIT ?
)
```

**MySQL 清理 SQL：**
```sql
DELETE FROM outbox_event
WHERE status IN (1, 3) AND COALESCE(done_at, created_at) < ?
ORDER BY created_at LIMIT ?
```

所有清理器类均支持通过构造函数自定义表名（使用与 `AbstractJdbcOutboxStore` 相同的正则校验）。

### 17.4 OutboxPurgeScheduler

定时组件，参照 `OutboxPoller` 设计：Builder 模式、`AutoCloseable`、守护线程、同步生命周期。

#### Builder

```java
OutboxPurgeScheduler scheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)  // 必填
    .purger(purger)                          // 必填
    .retention(Duration.ofDays(7))           // 默认: 7 天
    .batchSize(500)                          // 默认: 500
    .intervalSeconds(3600)                   // 默认: 3600（1 小时）
    .build();
```

| 参数 | 类型 | 默认值 | 必填 |
|------|------|--------|------|
| `connectionProvider` | `ConnectionProvider` | - | 是 |
| `purger` | `EventPurger` | - | 是 |
| `retention` | `Duration` | 7 天 | 否 |
| `batchSize` | `int` | 500 | 否 |
| `intervalSeconds` | `long` | 3600 | 否 |

#### 方法

```java
void start()    // 启动定时清理
void runOnce()  // 执行单次清理（循环批量删除直到 count < batchSize）
void close()    // 停止清理并关闭调度线程
```

#### 行为

- 每个清理周期计算截止时间为 `Instant.now().minus(retention)`
- 循环分批：每批使用独立的自动提交连接
- 当某批删除行数小于 `batchSize` 时停止（积压清空）
- 以 INFO 级别记录本次清理总数
- 异常被捕获并以 SEVERE 级别记录（不向上传播）
- 调用 `close()` 后再次调用 `start()` 必须抛出 `IllegalStateException`。

---

## 18. 死信事件管理

### 18.1 概述

超过 `maxAttempts` 或没有注册 Listener 的事件会被标记为 DEAD。框架提供工具来查询、计数和重放死信事件，无需手写 SQL。

### 18.2 OutboxStore SPI 方法

`OutboxStore` 接口包含用于死信操作的默认方法：

```java
default List<OutboxEvent> queryDead(Connection conn, String eventType, String aggregateType, int limit);
default int replayDead(Connection conn, String eventId);
default int countDead(Connection conn, String eventType);
```

- `queryDead` — 返回匹配可选过滤条件的死信事件（`null` 表示不过滤），按时间从旧到新排序
- `replayDead` — 将单个 DEAD 事件重置为 NEW 状态（返回更新的行数）
- `countDead` — 统计死信事件数量，可按事件类型过滤

### 18.3 DeadEventManager

`DeadEventManager`（`outbox.dead`）是一个便捷门面，通过 `ConnectionProvider` 内部管理连接生命周期：

```java
public final class DeadEventManager {
  public DeadEventManager(ConnectionProvider connectionProvider, OutboxStore outboxStore);

  public List<OutboxEvent> query(String eventType, String aggregateType, int limit);
  public boolean replay(String eventId);
  public int replayAll(String eventType, String aggregateType, int batchSize);
  public int count(String eventType);
}
```

**方法：**

| 方法 | 说明 |
|------|------|
| `query(eventType, aggregateType, limit)` | 查询死信事件，支持可选过滤（`null` 表示不过滤） |
| `replay(eventId)` | 将单个死信事件重置为 NEW；成功返回 `true` |
| `replayAll(eventType, aggregateType, batchSize)` | 分批重放所有匹配的死信事件；返回重放总数 |
| `count(eventType)` | 统计死信事件数量，可按事件类型过滤（`null` 表示不过滤） |

### 18.4 错误处理

所有 `DeadEventManager` 方法在遇到 `SQLException` 时以 `SEVERE` 级别记录日志：
- `query()` 失败时返回 `List.of()`
- `replay()` 失败时返回 `false`
- `replayAll()` 失败时返回已重放的数量并停止
- `count()` 失败时返回 `0`
