# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn test                           # Run all module tests
mvn -pl outbox-jdbc test           # Run JDBC module tests only
mvn -pl outbox-core -am package    # Build core module and dependencies
mvn -DskipTests package            # Build all jars without tests
mvn install -DskipTests && mvn -pl samples/outbox-demo exec:java  # Run demo
mvn install -DskipTests && mvn -f samples/outbox-spring-demo/pom.xml spring-boot:run  # Run Spring Boot demo
mvn install -DskipTests && mvn -pl samples/outbox-multi-ds-demo exec:java  # Run multi-datasource demo
```

Java 17 is the baseline. CI tests against Java 17 and 21.

## Architecture

Minimal, Spring-free outbox framework with JDBC persistence, hot-path enqueue, and poller fallback. Delivers events **at-least-once**; downstream systems must dedupe by `eventId`.

### Modules

- **outbox-core**: Core interfaces, dispatcher, poller, registries. Zero external dependencies.
- **outbox-jdbc**: JDBC outbox store hierarchy (`AbstractJdbcOutboxStore` with H2/MySQL/PostgreSQL subclasses), `JdbcTemplate` utility, manual transaction helpers (`JdbcTransactionManager`, `ThreadLocalTxContext`).
- **outbox-spring-adapter**: Optional `SpringTxContext` for Spring transaction integration.
- **outbox-micrometer**: Micrometer metrics bridge (`MicrometerMetricsExporter`) for Prometheus/Grafana.
- **benchmarks**: JMH benchmarks for write throughput, dispatch latency, and poller throughput (not published).
- **samples/outbox-demo**: Simple runnable demo with H2 (no Spring).
- **samples/outbox-spring-demo**: Spring Boot demo with REST API (standalone).
- **samples/outbox-multi-ds-demo**: Multi-datasource demo with two H2 databases (Orders + Inventory).

### Package Structure (JDBI-inspired)

```
outbox-core/src/main/java/
└── outbox/
    │  # Main API (root package)
    ├── OutboxWriter.java
    ├── EventEnvelope.java
    ├── EventType.java (interface)
    ├── AggregateType.java (interface)
    ├── StringEventType.java (record)
    ├── StringAggregateType.java (record)
    ├── EventListener.java (interface)
    ├── AfterCommitHook.java (interface)
    │
    │  # SPI - Extension Point Interfaces
    ├── spi/
    │   ├── TxContext.java
    │   ├── ConnectionProvider.java
    │   ├── OutboxStore.java
    │   ├── EventPurger.java
    │   └── MetricsExporter.java (contains Noop inner class)
    │
    │  # Model - Domain objects
    ├── model/
    │   ├── OutboxEvent.java (record)
    │   └── EventStatus.java
    │
    │  # Feature Packages
    ├── dispatch/
    │   ├── OutboxDispatcher.java
    │   ├── RetryPolicy.java (interface)
    │   ├── ExponentialBackoffRetryPolicy.java
    │   ├── InFlightTracker.java (interface)
    │   ├── DefaultInFlightTracker.java
    │   ├── QueuedEvent.java (record)
    │   ├── EventInterceptor.java (interface)
    │   └── UnroutableEventException.java
    │
    ├── poller/
    │   ├── OutboxPoller.java
    │   └── OutboxPollerHandler.java (interface)
    │
    ├── dead/
    │   └── DeadEventManager.java
    │
    ├── purge/
    │   └── OutboxPurgeScheduler.java
    │
    ├── registry/
    │   ├── ListenerRegistry.java (interface)
    │   └── DefaultListenerRegistry.java
    │
    └── util/
        ├── DaemonThreadFactory.java
        ├── JsonCodec.java (interface)
        └── DefaultJsonCodec.java

outbox-jdbc/src/main/java/
└── outbox/jdbc/
    │  # Shared JDBC utilities (root package)
    ├── JdbcTemplate.java
    ├── TableNames.java
    ├── OutboxStoreException.java
    ├── DataSourceConnectionProvider.java
    │
    │  # OutboxStore hierarchy
    ├── store/
    │   ├── AbstractJdbcOutboxStore.java
    │   ├── H2OutboxStore.java
    │   ├── MySqlOutboxStore.java
    │   ├── PostgresOutboxStore.java
    │   └── JdbcOutboxStores.java
    │
    │  # EventPurger hierarchy
    ├── purge/
    │   ├── AbstractJdbcEventPurger.java
    │   ├── H2EventPurger.java
    │   ├── MySqlEventPurger.java
    │   └── PostgresEventPurger.java
    │
    │  # Transaction management
    └── tx/
        ├── ThreadLocalTxContext.java
        └── JdbcTransactionManager.java
```

### Key Abstractions

- **TxContext**: Abstracts transaction lifecycle (`isTransactionActive()`, `currentConnection()`, `afterCommit()`, `afterRollback()`). Implementations: `ThreadLocalTxContext` (`outbox.jdbc.tx`, JDBC), `SpringTxContext` (Spring).
- **OutboxStore**: Persistence contract (`insertNew`, `markDone`, `markRetry`, `markDead`, `pollPending`, `claimPending`, `queryDead`, `replayDead`, `countDead`). Implemented by `AbstractJdbcOutboxStore` hierarchy in `outbox.jdbc.store`.
- **AbstractJdbcOutboxStore** (`outbox.jdbc.store`): Base JDBC outbox store with shared SQL, row mapper, and H2-compatible default `claimPending`. Subclasses: `H2OutboxStore`, `MySqlOutboxStore` (UPDATE...ORDER BY...LIMIT), `PostgresOutboxStore` (FOR UPDATE SKIP LOCKED + RETURNING).
- **JdbcOutboxStores** (`outbox.jdbc.store`): Static utility with ServiceLoader registry (`META-INF/services/outbox.jdbc.store.AbstractJdbcOutboxStore`) and `detect(DataSource)` auto-detection. Overloaded `detect(DataSource, JsonCodec)` creates new instances with a custom codec.
- **OutboxDispatcher**: Dual-queue event processor with hot queue (afterCommit callbacks) and cold queue (poller fallback). Created via `OutboxDispatcher.builder()`. Uses `InFlightTracker` for deduplication, `RetryPolicy` for exponential backoff, `EventInterceptor` for cross-cutting hooks, fair 2:1 hot/cold queue draining, and graceful shutdown with configurable drain timeout.
- **OutboxPoller**: Scheduled DB scanner as fallback when hot path fails. Created via `OutboxPoller.builder()`. Uses an `OutboxPollerHandler` to forward events. Two modes: single-node (default, `pollPending`) and multi-node (`claimLocking()` enables `claimPending` with row-level locks). Accepts optional `JsonCodec` via `.jsonCodec()` builder method.
- **JsonCodec**: Interface for `Map<String, String>` ↔ JSON encoding/decoding. `DefaultJsonCodec` is the built-in zero-dependency implementation (singleton via `JsonCodec.getDefault()`). Injectable into `AbstractJdbcOutboxStore`, `OutboxPoller`, and `JdbcOutboxStores.detect()` for users who prefer Jackson/Gson.
- **TableNames**: Shared utility in `outbox.jdbc` for table name validation (regex `[a-zA-Z_][a-zA-Z0-9_]*`).
- **AfterCommitHook**: Optional post-commit hook used by OutboxWriter to trigger hot-path processing (e.g., DispatcherCommitHook).
- **JdbcTemplate**: Lightweight JDBC helper (`update`, `query`, `updateReturning`) used by `AbstractJdbcOutboxStore` subclasses.
- **ListenerRegistry**: Maps `(aggregateType, eventType)` pairs to a single `EventListener`. Uses `AggregateType.GLOBAL` as default. Unroutable events (no listener) are immediately marked DEAD.
- **EventInterceptor**: Cross-cutting before/after hooks for audit, logging, metrics. `beforeDispatch` runs in registration order; `afterDispatch` in reverse. Replaces the old wildcard `registerAll()` pattern.
- **EventPurger**: SPI for deleting terminal events (DONE + DEAD) older than a cutoff. Implementations in `outbox.jdbc.purge`: `AbstractJdbcEventPurger` (base with subquery-based `DELETE`), `MySqlEventPurger` (`DELETE...ORDER BY...LIMIT`).
- **OutboxPurgeScheduler**: Scheduled component that purges terminal events on a configurable interval. Builder pattern, `AutoCloseable`, daemon threads (same lifecycle as `OutboxPoller`). Loops batches until `count < batchSize`.
- **DeadEventManager** (`outbox.dead`): Connection-managed facade for querying, counting, and replaying DEAD events. Constructor takes `ConnectionProvider` + `OutboxStore`.
- **MicrometerMetricsExporter** (`outbox.micrometer`): Micrometer-based `MetricsExporter` implementation with counters and gauges. Supports custom `namePrefix` for multi-instance use.

### Event Flow

1. `OutboxWriter.write()` inserts event to DB within caller's transaction
2. `afterCommit` callback invokes `AfterCommitHook` (e.g., DispatcherCommitHook -> OutboxDispatcher hot queue)
3. If hot queue full, event is dropped (logged) and poller picks it up later
4. OutboxDispatcher workers process events: run interceptors → find listener via `(aggregateType, eventType)` → execute → update status to DONE/RETRY/DEAD
5. Unroutable events (no listener found) are immediately marked DEAD (no retry)

## Coding Style

- 2-space indentation, braces on same line
- Package names: `outbox.<feature>` (e.g., `outbox.dispatch`, `outbox.spi`)
- Classes: `UpperCamelCase`, methods: `lowerCamelCase`, constants: `UPPER_SNAKE_CASE`
- No formatter configured; match existing style

## Testing

- JUnit Jupiter with `*Test` suffix (integration tests use `*IntegrationTest`)
- H2 in-memory database for tests
- Tests in `outbox-core/src/test`, `outbox-jdbc/src/test`, `outbox-spring-adapter/src/test`, and `outbox-micrometer/src/test`

## Release Process

Published to GitHub Packages. CI workflow (`.github/workflows/publish.yml`) auto-deploys on `v*` tags, validates tag matches POM version, and creates a GitHub Release with auto-generated notes.

Uses `versions-maven-plugin` for version updates. **Caveat**: `versions:set` won't update `samples/outbox-spring-demo/pom.xml` (uses Spring Boot parent) — must update manually.

```bash
# 1. Set release version (updates 7 of 8 pom.xml)
mvn versions:set -DnewVersion=X -DgenerateBackupPoms=false
# 2. Manually update samples/outbox-spring-demo/pom.xml <version>
# 3. Verify
mvn clean test
# 4. Commit and tag
git commit -am "release: X"
git tag vX
# 5. Bump to next dev version
mvn versions:set -DnewVersion=Y-SNAPSHOT -DgenerateBackupPoms=false
# 6. Manually update samples/outbox-spring-demo/pom.xml <version>
git commit -am "chore: bump version to Y-SNAPSHOT"
# 7. Push (tag triggers publish workflow → deploy + GitHub release)
git push && git push origin vX
```

Only library modules are published: `outbox-core`, `outbox-jdbc`, `outbox-spring-adapter`, `outbox-micrometer` (not samples or benchmarks).

## Documentation

- `README.md`: Project introduction, architecture diagram, and doc links
- `OBJECTIVE.md`: Project goals, constraints, non-goals, and acceptance criteria
- `SPEC.md`: Technical specification (17 sections: API contracts, data model, behavioral rules)
- `TUTORIAL.md`: Step-by-step guides with runnable code examples
- `CODE_REVIEW.md`: Prior review findings and fixes
