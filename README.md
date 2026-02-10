[![CI](https://github.com/leonlee/outbox/actions/workflows/ci.yml/badge.svg)](https://github.com/leonlee/outbox/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/leonlee/outbox)](https://github.com/leonlee/outbox/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/projects/jdk/17/)
[![Javadoc](https://img.shields.io/badge/Javadoc-latest-green)](https://leonlee.github.io/outbox/)
# outbox-java

Minimal, Spring-free outbox framework with JDBC persistence, optional hot-path enqueue, and poller/CDC fallback.

## Installation

Artifacts are published to [GitHub Packages](https://github.com/leonlee/outbox/packages). Current release: **0.3.0**.

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
  <version>0.3.0</version>
</dependency>

<!-- JDBC event store and transaction helpers (required for persistence) -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-jdbc</artifactId>
  <version>0.3.0</version>
</dependency>

<!-- Spring transaction integration (optional, only if using Spring) -->
<dependency>
  <groupId>outbox</groupId>
  <artifactId>outbox-spring-adapter</artifactId>
  <version>0.3.0</version>
</dependency>
```

> **Note:** GitHub Packages requires authentication. See [GitHub's guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages) for configuring your `~/.m2/settings.xml`.

## Modules

- `outbox-core`: core APIs, hooks, dispatcher, poller, and registries.
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

   afterCommit hook                                 poll pending
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

Hot path is optional: supply an `AfterCommitHook` (for example, `DispatcherCommitHook`) or omit it for CDC-only consumption.

## Requirements

- Java 17 or later

## Documentation

- [**OBJECTIVE.md**](OBJECTIVE.md) -- Project goals, constraints, non-goals, and acceptance criteria
- [**SPEC.md**](SPEC.md) -- Technical specification: API contracts, data model, behavioral rules, configuration, observability
- [**TUTORIAL.md**](TUTORIAL.md) -- Step-by-step guides with runnable code examples

## Notes

- Delivery is **at-least-once**. Use `eventId` for downstream dedupe.
- Hot queue drops (DispatcherCommitHook) do not throw; the poller (if enabled) or CDC should pick up the event.
