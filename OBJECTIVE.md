# Outbox Framework Objectives

Project goals, constraints, and acceptance criteria for the outbox-java framework.

## Goals

Build a framework that:

1. Persists a unified event record into an outbox table within the current business DB transaction.
2. After successful transaction commit, optionally invokes a WriterHook (fast path).
3. OutboxDispatcher executes registered EventListeners (send to MQ, update caches, call APIs, etc.).
4. On success, OutboxDispatcher updates outbox status to DONE; on failure updates to RETRY/DEAD.
5. A low-frequency OutboxPoller (optional) scans DB as fallback only (node crash, enqueue downgrade, missed enqueue) and
   forwards unfinished events to a handler; CDC can also serve as the fallback path.
6. Delivery semantics: **at-least-once**; duplicates are allowed and must be handled downstream by `eventId`.
7. `Outbox` composite builder (`singleNode`/`multiNode`/`ordered`/`writerOnly`) is the recommended entry point, wiring
   dispatcher, poller, and writer with correct defaults for each deployment topology.
8. Supports delayed message delivery via `EventEnvelope.availableAt()`/`deliverAfter()`, where delayed events bypass the
   hot path and are delivered by the poller at the scheduled time.
9. Listeners may return `DispatchResult.RetryAfter(Duration)` to defer re-delivery without counting against
   `maxAttempts`, or throw `RetryAfterException` to defer with an attempt increment.
10. EventInterceptor provides cross-cutting before/after dispatch hooks for audit, logging, and metrics.
11. Dead event tooling (`DeadEventManager`) enables querying, counting, and replaying DEAD events.
12. Automatic purging of terminal events via `OutboxPurgeScheduler` (status-based or age-based for CDC mode).
13. Pluggable `JsonCodec` interface for metadata encoding (built-in `DefaultJsonCodec` or user-provided Jackson/Gson).
14. Micrometer metrics integration via optional `outbox-micrometer` module.
15. Spring Boot auto-configuration via `outbox-spring-boot-starter` with `@OutboxListener` annotation support.

## Constraints

- Core MUST NOT depend on Spring (no Spring TX, no JdbcTemplate).
- DB access MUST use standard JDBC.
- Spring integration is optional and implemented via an adapter module.

## Non-Goals

- Exactly-once end-to-end.
- Distributed transactions.
- Built-in message broker integration (listeners implement this).

## Delivery Semantics

- Delivery is **at-least-once**. Use `eventId` for downstream dedupe.
- Hot queue drops (DispatcherWriterHook) do not throw; the poller (if enabled) or CDC should pick up the event.

---

## Acceptance Tests

### Atomicity

- Begin tx manually, write event, rollback
- **Expect**: outbox row not present

### Commit + Fast Path

- Begin tx, write event, commit
- **Expect**: dispatcher receives HOT event; listener invoked; outbox status DONE

### Queue Overflow Downgrade

- Hot queue capacity small, force drop
- **Expect**: write returns OK, outbox row NEW
- Start poller (or run CDC handler)
- **Expect**: row processed to DONE

### Retry/Dead

- Listener fails repeatedly
- **Expect**: attempts increments, RETRY status
- After maxAttempts
- **Expect**: DEAD status

### Type-Safe EventType

- Register listener with enum EventType
- Publish event using same enum
- **Expect**: listener invoked

### Type-Safe AggregateType

- Publish event with enum AggregateType
- **Expect**: aggregateType() returns enum name string

### Delayed Delivery

- Write event with `deliverAfter(Duration.ofMinutes(5))`
- **Expect**: event is not dispatched via hot path
- **Expect**: poller delivers event after the delay has elapsed

### Deferred Retry (RetryAfter)

- Listener returns `DispatchResult.retryAfter(Duration.ofSeconds(30))`
- **Expect**: event status set to PENDING with future `available_at`; attempts NOT incremented

### Dead Event Replay

- Event exhausts maxAttempts, becomes DEAD
- Call `DeadEventManager.replayDead(eventId)`
- **Expect**: event status reset to PENDING for re-processing

### Writer-Only / CDC Mode

- Build with `Outbox.writerOnly()`
- Write events within transaction
- **Expect**: events persisted; no dispatcher or poller started; CDC captures changes externally

### Event Purging

- Configure `OutboxPurgeScheduler` with retention period
- Write events, process to DONE
- **Expect**: terminal events older than retention period are deleted in batches

### Multi-Node Claim Locking

- Multiple poller instances with `claimLocking()` enabled
- **Expect**: each event claimed and processed by exactly one node (row-level locks)

### Interceptors

- Register `EventInterceptor` with before/after hooks
- **Expect**: `beforeDispatch` called before listener; `afterDispatch` called after (reverse order)

### Spring Boot Auto-Configuration

- Add `outbox-spring-boot-starter` dependency, configure `application.properties`
- Annotate method with `@OutboxListener(eventType = "...", aggregateType = "...")`
- **Expect**: listener auto-registered; outbox fully wired with zero manual configuration
