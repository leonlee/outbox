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

Java 17 is the baseline.

## Architecture

Minimal, Spring-free outbox framework with JDBC persistence, hot-path enqueue, and poller fallback. Delivers events **at-least-once**; downstream systems must dedupe by `eventId`.

### Modules

- **outbox-core**: Core interfaces, dispatcher, poller, registries. Zero external dependencies.
- **outbox-jdbc**: JDBC event store hierarchy (`AbstractJdbcEventStore` with H2/MySQL/PostgreSQL subclasses), `JdbcTemplate` utility, manual transaction helpers (`JdbcTransactionManager`, `ThreadLocalTxContext`).
- **outbox-spring-adapter**: Optional `SpringTxContext` for Spring transaction integration.
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
    │   ├── EventStore.java
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
    ├── registry/
    │   ├── ListenerRegistry.java (interface)
    │   └── DefaultListenerRegistry.java
    │
    └── util/
        ├── DaemonThreadFactory.java
        └── JsonCodec.java

outbox-jdbc/src/main/java/
└── outbox/jdbc/
    ├── AbstractJdbcEventStore.java   (base: shared SQL, row mapper, JDBC boilerplate)
    ├── H2EventStore.java             (inherits default subquery-based claim)
    ├── MySqlEventStore.java          (UPDATE...ORDER BY...LIMIT claim)
    ├── PostgresEventStore.java       (FOR UPDATE SKIP LOCKED + RETURNING claim)
    ├── JdbcEventStores.java          (ServiceLoader registry + detect())
    ├── JdbcTemplate.java
    ├── EventStoreException.java
    ├── DataSourceConnectionProvider.java
    ├── JdbcTransactionManager.java
    └── ThreadLocalTxContext.java
```

### Key Abstractions

- **TxContext**: Abstracts transaction lifecycle (`isTransactionActive()`, `currentConnection()`, `afterCommit()`, `afterRollback()`). Implementations: `ThreadLocalTxContext` (JDBC), `SpringTxContext` (Spring).
- **EventStore**: Persistence contract (`insertNew`, `markDone`, `markRetry`, `markDead`, `pollPending`, `claimPending`). Implemented by `AbstractJdbcEventStore` hierarchy.
- **AbstractJdbcEventStore**: Base JDBC event store with shared SQL, row mapper, and H2-compatible default `claimPending`. Subclasses: `H2EventStore`, `MySqlEventStore` (UPDATE...ORDER BY...LIMIT), `PostgresEventStore` (FOR UPDATE SKIP LOCKED + RETURNING).
- **JdbcEventStores**: Static utility with ServiceLoader registry and `detect(DataSource)` auto-detection.
- **OutboxDispatcher**: Dual-queue event processor with hot queue (afterCommit callbacks) and cold queue (poller fallback). Created via `OutboxDispatcher.builder()`. Uses `InFlightTracker` for deduplication, `RetryPolicy` for exponential backoff, `EventInterceptor` for cross-cutting hooks, fair 2:1 hot/cold queue draining, and graceful shutdown with configurable drain timeout.
- **OutboxPoller**: Scheduled DB scanner as fallback when hot path fails. Created via `OutboxPoller.builder()`. Uses an `OutboxPollerHandler` to forward events. Supports claim-based locking via `ownerId`/`lockTimeout` for multi-instance deployments.
- **AfterCommitHook**: Optional post-commit hook used by OutboxWriter to trigger hot-path processing (e.g., DispatcherCommitHook).
- **JdbcTemplate**: Lightweight JDBC helper (`update`, `query`, `updateReturning`) used by `AbstractJdbcEventStore` subclasses.
- **ListenerRegistry**: Maps `(aggregateType, eventType)` pairs to a single `EventListener`. Uses `AggregateType.GLOBAL` as default. Unroutable events (no listener) are immediately marked DEAD.
- **EventInterceptor**: Cross-cutting before/after hooks for audit, logging, metrics. `beforeDispatch` runs in registration order; `afterDispatch` in reverse. Replaces the old wildcard `registerAll()` pattern.

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
- Tests in `outbox-core/src/test`, `outbox-jdbc/src/test`, and `outbox-spring-adapter/src/test`

## Documentation

- `README.md`: Project introduction, architecture diagram, and doc links
- `OBJECTIVE.md`: Project goals, constraints, non-goals, and acceptance criteria
- `SPEC.md`: Technical specification (16 sections: API contracts, data model, behavioral rules)
- `TUTORIAL.md`: Step-by-step guides with runnable code examples
- `CODE_REVIEW.md`: Prior review findings and fixes
