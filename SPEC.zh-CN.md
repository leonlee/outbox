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
8. [JDBC Event Store](#8-jdbc-event-store)
9. [OutboxDispatcher](#9-outboxdispatcher)
10. [OutboxPoller](#10-outboxpoller)
11. [注册中心](#11-注册中心)
12. [重试策略](#12-重试策略)
13. [背压与降级](#13-背压与降级)
14. [配置](#14-配置)
15. [可观测性](#15-可观测性)
16. [线程安全](#16-线程安全)

---

## 1. 架构概览

### 1.1 核心组件

| 组件 | 职责 |
|------|------|
| **OutboxWriter** | 业务代码在事务中调用的写入 API |
| **TxContext** | 事务生命周期抽象（afterCommit / afterRollback） |
| **EventStore** | 通过 `java.sql.Connection` 完成事件的增删改查 |
| **OutboxDispatcher** | 热/冷双队列 + Worker 线程池，执行 Listener 并更新状态 |
| **ListenerRegistry** | 事件类型到 Listener 的映射 |
| **OutboxPoller** | 低频扫表兜底，将未处理事件交给 Handler |
| **InFlightTracker** | 内存级去重，防止同一事件并发处理 |

### 1.2 事件流转

```
热路径（快速）
Business TX -> OutboxWriter.write()
  -> EventStore.insertNew()       // 在事务内
  -> TxContext.afterCommit(...)    // 注册提交后回调
Commit
  -> AfterCommitHook.onCommit(event)
  -> OutboxDispatcher.enqueueHot(...)
  -> OutboxDispatcher.process()
  -> EventStore.markDone/Retry/Dead()

冷路径（兜底）
OutboxPoller.poll()
  -> EventStore.pollPending()/claimPending()
  -> OutboxPollerHandler.handle(event, attempts)
  -> OutboxDispatcher.enqueueCold(...)
  -> OutboxDispatcher.process()
  -> EventStore.markDone/Retry/Dead()
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
- `outbox` — 主 API：OutboxWriter、EventEnvelope、EventType、AggregateType、EventListener、AfterCommitHook
- `outbox.spi` — 扩展点接口：TxContext、ConnectionProvider、EventStore、MetricsExporter
- `outbox.model` — 领域对象：OutboxEvent、EventStatus
- `outbox.dispatch` — OutboxDispatcher、重试策略、InFlight 追踪
- `outbox.poller` — OutboxPoller、OutboxPollerHandler
- `outbox.registry` — Listener 注册中心
- `outbox.util` — JsonCodec（无外部 JSON 依赖）

### 2.2 outbox-jdbc

JDBC EventStore 继承体系与手动事务管理工具。

类：
- `AbstractJdbcEventStore` — 基类，共享 SQL、行映射器，默认使用 H2 兼容的 claim 实现
- `H2EventStore` — H2（继承默认的子查询式 claim）
- `MySqlEventStore` — MySQL / TiDB（`UPDATE...ORDER BY...LIMIT` claim）
- `PostgresEventStore` — PostgreSQL（`FOR UPDATE SKIP LOCKED` + `RETURNING` claim）
- `JdbcEventStores` — ServiceLoader 注册表 + `detect(DataSource)` 自动探测
- `ThreadLocalTxContext` — 基于 ThreadLocal 的 TxContext，用于手动事务管理
- `JdbcTransactionManager` — 手动 JDBC 事务辅助类
- `DataSourceConnectionProvider` — 从 DataSource 获取连接的 ConnectionProvider

### 2.3 outbox-spring-adapter

可选的 Spring 集成模块。

类：
- `SpringTxContext` — 基于 Spring TransactionSynchronizationManager 实现 TxContext

### 2.4 samples/outbox-demo

独立 H2 示例（无 Spring）。

### 2.5 samples/outbox-spring-demo

Spring Boot REST API 示例。

### 2.6 samples/outbox-multi-ds-demo

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
  public OutboxWriter(TxContext txContext, EventStore eventStore);
  public OutboxWriter(TxContext txContext, EventStore eventStore, AfterCommitHook afterCommitHook);

  public String write(EventEnvelope event);
  public String write(String eventType, String payloadJson);
  public String write(EventType eventType, String payloadJson);
  public List<String> writeAll(List<EventEnvelope> events);
}
```

语义：
- 必须在活跃事务中调用（通过 TxContext 校验）
- 使用 `TxContext.currentConnection()` 在当前事务内插入 outbox 行（状态 NEW）
- 若提供了 Hook，必须注册 `TxContext.afterCommit(() -> afterCommitHook.onCommit(event))`
- Hook 抛异常时不得传播给调用方（仅记录日志）
- 未提供 Hook 时不执行任何提交后动作（由 Poller / CDC 负责后续投递）

### 7.2 AfterCommitHook

```java
@FunctionalInterface
public interface AfterCommitHook {
  void onCommit(EventEnvelope event);

  AfterCommitHook NOOP = event -> {};
}
```

- 事务提交后触发（可选）
- 用于接入热路径 Dispatcher 或外部通知

---

## 8. JDBC Event Store

### 8.1 接口

```java
public interface EventStore {
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
    .eventStore(eventStore)                  // 必填
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
boolean hasColdQueueCapacity()         // 冷队列是否有空间
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
    .eventStore(eventStore)                  // 必填
    .handler(handler)                        // 必填
    .skipRecent(Duration.ofSeconds(1))       // 默认: Duration.ZERO
    .batchSize(50)                           // 默认: 50
    .intervalMs(5000)                        // 默认: 5000
    .metrics(metricsExporter)                // 默认: MetricsExporter.NOOP
    .ownerId("poller-1")                     // 默认: 设置 lockTimeout 时自动生成
    .lockTimeout(Duration.ofMinutes(5))      // 默认: 5 分钟
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

提供 `ownerId` 时，Poller 使用 claim 锁定机制：

- **加锁**：原子设置 `locked_by` 和 `locked_at`
- **过期**：超过 `lockTimeout` 的锁视为已过期，可被重新 claim
- **释放**：`markDone` / `markRetry` / `markDead` 清除 `locked_by` 和 `locked_at`
- **数据库差异**：PostgreSQL 用 `FOR UPDATE SKIP LOCKED` + `RETURNING`；MySQL 用 `UPDATE...ORDER BY...LIMIT`；H2 用子查询式两阶段 claim

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

- 每个解码成功的事件调用一次
- 返回 false 中止当前轮询轮次（背压信号）

### 10.6 CDC 替代方案（可选）

高 QPS 场景下，CDC 可替代进程内 Poller 和热路径 Hook：

- 构造 `OutboxWriter` 时不传 Hook（或传 `AfterCommitHook.NOOP`）
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

### 13.4 热队列满时的行为（DispatcherCommitHook）

- `write()` 不得抛异常
- DispatcherCommitHook 记录 WARNING 日志并递增指标
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
    .eventStore(eventStore)
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

### 15.2 日志级别

| 级别 | 事件 |
|------|------|
| WARNING | 热队列丢弃（DispatcherCommitHook） |
| ERROR | 进入 DEAD 状态 |
| ERROR | Dispatcher / Poller 循环异常 |
| SEVERE | 解码失败（如 headers 格式异常） |

热队列丢弃告警由 DispatcherCommitHook 发出。未安装 Hook（纯 CDC 模式）时不会产生告警或指标。

### 15.3 幂等性要求

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
| ThreadLocalTxContext | ThreadLocal 存储 |
