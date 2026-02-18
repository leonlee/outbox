[English](README.md) | [中文](README.zh-CN.md)

[![CI](https://github.com/leonlee/outbox/actions/workflows/ci.yml/badge.svg)](https://github.com/leonlee/outbox/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/leonlee/outbox)](https://github.com/leonlee/outbox/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/projects/jdk/17/)
[![Javadoc](https://img.shields.io/badge/Javadoc-latest-green)](https://leonlee.github.io/outbox/)
# outbox-java

轻量 Outbox 框架，无需依赖 Spring。基于 JDBC 实现持久化，支持热路径直推与轮询器/CDC 兜底两种投递方式。

## 安装

构件托管在 [GitHub Packages](https://github.com/leonlee/outbox/packages)，当前版本 **0.6.0**。

先在 `pom.xml` 中添加仓库地址：

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/leonlee/outbox</url>
  </repository>
</repositories>
```

再按需引入依赖：

```xml
<!-- 核心：API、Dispatcher、Poller、Registry（必选） -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-core</artifactId>
  <version>0.6.0</version>
</dependency>

<!-- JDBC 实现：OutboxStore 及事务管理（持久化必选） -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-jdbc</artifactId>
  <version>0.6.0</version>
</dependency>

<!-- Spring 适配器（可选，仅 Spring 项目使用） -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-spring-adapter</artifactId>
  <version>0.6.0</version>
</dependency>

<!-- Micrometer 监控桥接（可选，用于 Prometheus/Grafana） -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-micrometer</artifactId>
  <version>0.6.0</version>
</dependency>
```

> **注意：** GitHub Packages 需要认证，详见 [GitHub 官方文档](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages)中关于 `~/.m2/settings.xml` 的配置说明。

## 模块

- `outbox-core`：核心 API、Hook、Dispatcher、Poller、Registry，零外部依赖。
- `outbox-jdbc`：JDBC OutboxStore 实现及事务管理工具。
- `outbox-spring-adapter`：可选的 Spring `TxContext` 适配。
- `outbox-micrometer`：Micrometer 监控桥接，支持 Prometheus/Grafana。
- `samples/outbox-demo`：纯 JDBC 示例（H2，无 Spring）。
- `samples/outbox-spring-demo`：Spring Boot 示例。
- `samples/outbox-multi-ds-demo`：多数据源示例（双 H2 库）。

## 架构

```text
+----------------------------------------------------------------+
|                      Transaction Scope                          |
|                                                                 |
|  +------------------------+  write()  +------------------+      |
|  |  Application / Domain  | --------> |   OutboxWriter   |      |
|  +------------------------+           +--------+---------+      |
|                                                | insert         |
|                                                v                |
|                                       +------------------+      |
|                                       |    OutboxStore   |      |
|                                       +--------+---------+      |
|                                                | persist        |
|                                                v                |
|                                       +------------------+      |
|                                       |   Outbox Table   |      |
|                                       +------------------+      |
+------------+-------------------------------+--------------------+
             |                               |
    afterCommit hook                    poll pending
             |                               |
             v                               v
       +-----------+                  +--------------+
       | Hot Queue |                  | OutboxPoller |
       +-----+-----+                 +------+-------+
             |                               |
             v            enqueue cold       |
    +------------------+                     |
    | OutboxDispatcher | <-------------------+
    +--------+---------+
             |
             v
    +------------------+  onEvent()  +--------------+
    | ListenerRegistry | ----------> |  Listener A  |
    +--------+---------+             +--------------+
             |                       +--------------+
             +---------------------> |  Listener B  |
                                     +--------------+

    OutboxDispatcher --- mark DONE/RETRY/DEAD ---> OutboxStore
```

热路径是可选的——配置 `WriterHook`（如 `DispatcherWriterHook`）即可启用；不配置则可由 Poller 或 CDC 消费。

## 工作原理

- **事务内写入**：`OutboxWriter.write(...)` 使用业务事务的同一 JDBC 连接写入 `outbox_event`。
- **提交后 Hook**：`DispatcherWriterHook` 在提交后将事件入热队列；队列满时事件留在 DB。
- **派发**：`OutboxDispatcher` 消费热/冷队列，路由到唯一监听器，并更新状态 `DONE`/`RETRY`/`DEAD`。
- **兜底**：`OutboxPoller` 定期扫描/Claim 待处理行并入冷队列。
- **至少一次**：监听器可能重复收到事件，下游需按 `eventId` 去重。
- **清理**：`OutboxPurgeScheduler` 定期删除超过保留期的终态事件（DONE/DEAD），防止表膨胀。

## 运行模式

- **热路径 + Poller（默认）**：启用 `DispatcherWriterHook` 并启动 `OutboxPoller`，低延迟 + 兜底。
- **仅 Poller**：不配置 Hook，启动 `OutboxPoller`，更简单但延迟更高。
- **仅 CDC**：不配置 Hook 也不启动 Poller；由 CDC 消费并自行去重与保留策略。

## 调优与背压

- `workerCount` 控制最大并发 listener 数。
- `hotQueueCapacity`/`coldQueueCapacity` 控制内存队列容量。
- `skipRecent` 减少与刚提交事件的竞争。
- `claimLocking(ownerId, lockTimeout)` 支持多实例锁定。
- `maxAttempts` 和 `RetryPolicy` 控制重试策略。

## 事件保留与清理

Outbox 表是临时缓冲区而非 outbox 存储。终态事件（DONE、DEAD）应在保留期后被清理以防表膨胀：

```java
OutboxPurgeScheduler purgeScheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)
    .purger(new H2EventPurger())       // 或 MySqlEventPurger、PostgresEventPurger
    .retention(Duration.ofDays(7))     // 默认: 7 天
    .batchSize(500)                    // 默认: 500
    .intervalSeconds(3600)             // 默认: 1 小时
    .build();
purgeScheduler.start();
```

如需归档事件用于审计，应在 `EventListener` 中完成，趁事件被清理之前。

## 失败与投递语义

- 语义为 **at-least-once**，下游必须按 `eventId` 去重。
- Listener 异常触发 `RETRY`，超过 `maxAttempts` 进入 `DEAD`。
- 状态更新失败时事件仍保留在 DB，之后可重试。

## 有序投递

当业务场景要求同一聚合根的事件按顺序投递时（例如 `OrderCreated` 必须先于
`OrderShipped`），可使用**仅 Poller** 模式配合单分发线程：

1. **不配置 `WriterHook`** — 不注入 `DispatcherWriterHook`（禁用热路径）。
2. **单节点 `OutboxPoller`** — 由一个 Poller 实例按 DB 插入顺序读取事件。
3. **`workerCount(1)`** — 单个分发线程顺序处理事件。

Poller 按 `ORDER BY created_at` 读取待处理事件，单线程按该顺序依次投递给
Listener。由于数据库 I/O（轮询）才是吞吐瓶颈，而非内存中的分发，因此单线程
完全能跟上。

**注意——重试会打破顺序。** 如果事件 A 失败并进入退避重试，同一聚合根的后续事件
B 可能在 A 等待重试期间被轮询并投递。要保证严格有序，设置 `maxAttempts(1)` 使
失败事件直接进入 DEAD 而不重试。可通过[死信事件管理](TUTORIAL.zh-CN.md#11-死信事件管理)
检查并手动重放。

代价：延迟更高（取决于轮询间隔，而非热路径的即时投递）。对于不需要顺序的事件，
建议使用默认的热路径 + Poller 模式以获得最低延迟。

## 环境要求

- Java 17+

## 文档

- [**OBJECTIVE.md**](OBJECTIVE.zh-CN.md) -- 项目目标、约束与验收标准
- [**SPEC.md**](SPEC.zh-CN.md) -- 技术规范：API 契约、数据模型、行为规则、配置与可观测性
- [**TUTORIAL.md**](TUTORIAL.zh-CN.md) -- 手把手教程，含可直接运行的代码示例

## 补充说明

- 语义为 **at-least-once**，下游需按 `eventId` 去重。
- 热队列满时 DispatcherWriterHook 不会抛异常，事件仍安全落库，由 Poller 或 CDC 兜底投递。
