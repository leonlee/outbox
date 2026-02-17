[English](README.md) | [中文](README.zh-CN.md)

[![CI](https://github.com/leonlee/outbox/actions/workflows/ci.yml/badge.svg)](https://github.com/leonlee/outbox/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/leonlee/outbox)](https://github.com/leonlee/outbox/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/projects/jdk/17/)
[![Javadoc](https://img.shields.io/badge/Javadoc-latest-green)](https://leonlee.github.io/outbox/)
# outbox-java

Minimal, Spring-free outbox framework with JDBC persistence, optional hot-path enqueue, and poller/CDC fallback.

## Installation

Artifacts are published to [GitHub Packages](https://github.com/leonlee/outbox/packages). Current release: **0.6.0**.

Add the GitHub Packages repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/leonlee/outbox</url>
  </repository>
</repositories>
```

Then add the dependencies you need:

```xml
<!-- Core APIs, dispatcher, poller, registries (required) -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-core</artifactId>
  <version>0.6.0</version>
</dependency>

<!-- JDBC outbox store and transaction helpers (required for persistence) -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-jdbc</artifactId>
  <version>0.6.0</version>
</dependency>

<!-- Spring transaction integration (optional, only if using Spring) -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-spring-adapter</artifactId>
  <version>0.6.0</version>
</dependency>

<!-- Micrometer metrics bridge for Prometheus/Grafana (optional) -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-micrometer</artifactId>
  <version>0.6.0</version>
</dependency>
```

> **Note:** GitHub Packages requires authentication. See [GitHub's guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages) for configuring your `~/.m2/settings.xml`.

## Modules

- `outbox-core`: core APIs, hooks, dispatcher, poller, and registries.
- `outbox-jdbc`: JDBC outbox store and transaction helpers.
- `outbox-spring-adapter`: optional `TxContext` implementation for Spring.
- `outbox-micrometer`: Micrometer metrics bridge for Prometheus/Grafana.
- `samples/outbox-demo`: minimal, non-Spring demo (H2).
- `samples/outbox-spring-demo`: Spring demo app.
- `samples/outbox-multi-ds-demo`: multi-datasource demo (two H2 databases).

## Architecture

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

Hot path is optional: supply an `AfterCommitHook` (for example, `DispatcherCommitHook`) or omit it for CDC-only consumption.

## How It Works

- **Write inside TX**: `OutboxWriter.write(...)` inserts into `outbox_event` using the same JDBC connection as your business work.
- **After-commit hook**: `DispatcherCommitHook` enqueues the event into the hot queue after commit. If the queue is full, the event stays in the DB.
- **Dispatch**: `OutboxDispatcher` drains hot/cold queues, routes to a single listener, and updates status to `DONE`, `RETRY`, or `DEAD`.
- **Fallback**: `OutboxPoller` periodically scans/claims pending rows and enqueues them into the cold queue.
- **At-least-once**: listeners may see duplicates. Always dedupe downstream by `eventId`.
- **Purge**: `OutboxPurgeScheduler` periodically deletes terminal events (DONE/DEAD) older than a configurable retention period to prevent table bloat.

## Operating Modes

- **Hot + Poller (default)**: Use `DispatcherCommitHook` and start `OutboxPoller` for low latency plus durable fallback.
- **Poller-only**: Omit the hook, start `OutboxPoller`. Simpler wiring, higher latency.
- **CDC-only**: Omit both hook and poller. CDC reads the table; you manage dedupe and retention.

## Tuning and Backpressure

- `workerCount` controls max concurrent listener executions.
- `hotQueueCapacity`/`coldQueueCapacity` bound in-memory buffering.
- `skipRecent` avoids racing very recent inserts with the hot path.
- `ownerId` + `lockTimeout` enable multi-instance poller locking.
- `maxAttempts` and `RetryPolicy` control retries and backoff.

## Event Retention / Purge

The outbox table is a transient buffer, not an outbox store. Terminal events (DONE, DEAD) should be purged after a retention period to prevent table bloat:

```java
OutboxPurgeScheduler purgeScheduler = OutboxPurgeScheduler.builder()
    .connectionProvider(connectionProvider)
    .purger(new H2EventPurger())       // or MySqlEventPurger, PostgresEventPurger
    .retention(Duration.ofDays(7))     // default: 7 days
    .batchSize(500)                    // default: 500
    .intervalSeconds(3600)             // default: 1 hour
    .build();
purgeScheduler.start();
```

If clients need to archive events for audit, they should do so in their `EventListener` before events are purged.

## Failure and Delivery Semantics

- Delivery is **at-least-once**. Downstream must dedupe by `eventId`.
- Listener exceptions trigger `RETRY` with backoff; after `maxAttempts`, events go `DEAD`.
- If status updates fail, the event remains in DB and may be retried later.

## Requirements

- Java 17 or later

## Documentation

- [**OBJECTIVE.md**](OBJECTIVE.md) -- Project goals, constraints, non-goals, and acceptance criteria
- [**SPEC.md**](SPEC.md) -- Technical specification: API contracts, data model, behavioral rules, configuration, observability
- [**TUTORIAL.md**](TUTORIAL.md) -- Step-by-step guides with runnable code examples

## Notes

- Delivery is **at-least-once**. Use `eventId` for downstream dedupe.
- Hot queue drops (DispatcherCommitHook) do not throw; the poller (if enabled) or CDC should pick up the event.
