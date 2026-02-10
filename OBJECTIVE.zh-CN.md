[English](OBJECTIVE.md) | [中文](OBJECTIVE.zh-CN.md)

# Outbox 框架目标

outbox-java 框架的项目目标、约束与验收标准。

## 目标

框架要做到：

1. 在当前业务事务中，将事件记录原子写入 outbox 表。
2. 事务提交后，可选地触发 AfterCommitHook（快速路径）。
3. OutboxDispatcher 调用已注册的 EventListener 完成后续动作（推送 MQ、刷缓存、调接口等）。
4. 处理成功则标记 DONE，失败则标记 RETRY 或 DEAD。
5. OutboxPoller（可选）作为兜底，低频扫表捡漏（宕机、入队失败等场景），将未完成事件交给 Handler 处理；也可用 CDC 替代。
6. 投递语义 **at-least-once**——允许重复投递，下游需按 `eventId` 自行去重。

## 约束

- 核心模块禁止依赖 Spring（不用 Spring TX，不用 Spring JdbcTemplate）。
- 数据库访问只用标准 JDBC。
- Spring 集成通过独立的 adapter 模块提供。

## 非目标

- 端到端 exactly-once。
- 分布式事务。

## 投递语义

- **at-least-once**，下游按 `eventId` 去重。
- 热队列满时 DispatcherCommitHook 静默丢弃（不抛异常），由 Poller 或 CDC 兜底。

---

## 验收测试

### 原子性

- 手动开启事务 → 写入事件 → 回滚
- **预期**：outbox 表无对应记录

### 提交 + 快速路径

- 开启事务 → 写入事件 → 提交
- **预期**：Dispatcher 收到 HOT 事件，Listener 被调用，outbox 状态变为 DONE

### 队列满降级

- 将热队列容量设得很小，强制触发丢弃
- **预期**：write 正常返回，outbox 行状态为 NEW
- 启动 Poller（或 CDC）
- **预期**：事件最终被处理为 DONE

### 重试 / 死信

- Listener 持续抛异常
- **预期**：attempts 递增，状态变为 RETRY
- 达到 maxAttempts 后
- **预期**：状态变为 DEAD

### 类型安全 EventType

- 用枚举 EventType 注册 Listener
- 用同一枚举发布事件
- **预期**：Listener 被调用

### 类型安全 AggregateType

- 用枚举 AggregateType 发布事件
- **预期**：aggregateType() 返回枚举名称字符串
