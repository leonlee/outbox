# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn test                           # Run all module tests
mvn -pl outbox-jdbc test           # Run JDBC module tests only
mvn -pl outbox-core -am package    # Build core module and dependencies
mvn -DskipTests package            # Build all jars without tests
mvn install -DskipTests && mvn -pl outbox-demo exec:java  # Run demo
mvn install -DskipTests && mvn -f outbox-spring-demo/pom.xml spring-boot:run  # Run Spring Boot demo
```

Java 17 is the baseline.

## Architecture

Minimal, Spring-free outbox framework with JDBC persistence, hot-path enqueue, and poller fallback. Delivers events **at-least-once**; downstream systems must dedupe by `eventId`.

### Modules

- **outbox-core**: Core interfaces, dispatcher, poller, registries. Zero external dependencies.
- **outbox-jdbc**: JDBC repository implementation and manual transaction helpers (`JdbcTransactionManager`, `ThreadLocalTxContext`).
- **outbox-spring-adapter**: Optional `SpringTxContext` for Spring transaction integration.
- **outbox-demo**: Simple runnable demo with H2 (no Spring).
- **outbox-spring-demo**: Spring Boot demo with REST API (standalone).

### Key Abstractions

- **TxContext**: Abstracts transaction lifecycle (`isTransactionActive()`, `currentConnection()`, `afterCommit()`, `afterRollback()`). Implementations: `ThreadLocalTxContext` (JDBC), `SpringTxContext` (Spring).
- **OutboxRepository**: Persistence contract (`insertNew`, `markDone`, `markRetry`, `markDead`, `pollPending`). Implemented by `JdbcOutboxRepository`.
- **Dispatcher**: Dual-queue event processor with hot queue (afterCommit callbacks) and cold queue (poller fallback). Uses `InFlightTracker` for deduplication and `RetryPolicy` for exponential backoff.
- **OutboxPoller**: Scheduled DB scanner as fallback when hot path fails.
- **ListenerRegistry**: Maps event types to `EventListener` instances. Supports wildcard "*" registration for audit/logging listeners.

### Event Flow

1. `OutboxClient.publish()` inserts event to DB within caller's transaction
2. `afterCommit` callback enqueues to Dispatcher's hot queue
3. If hot queue full, event is dropped (logged) and poller picks it up later
4. Dispatcher workers process events, update status to DONE/RETRY/DEAD

## Coding Style

- 2-space indentation, braces on same line
- Package names: `outbox.<module>` (e.g., `outbox.core.dispatch`)
- Classes: `UpperCamelCase`, methods: `lowerCamelCase`, constants: `UPPER_SNAKE_CASE`
- No formatter configured; match existing style

## Testing

- JUnit Jupiter with `*Test` suffix (integration tests use `*IntegrationTest`)
- H2 in-memory database for tests
- Tests in `outbox-jdbc/src/test` and `outbox-spring-adapter/src/test`

## Documentation

- `README.md`: Usage examples and quick start
- `spec.md`: Detailed behavioral specification (16 sections)
- `CODE_REVIEW.md`: Prior review findings and fixes
