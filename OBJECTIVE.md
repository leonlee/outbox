
# Outbox Framework Objectives

Project goals, constraints, and acceptance criteria for the outbox-java framework.

## Goals

Build a framework that:

1. Persists a unified event record into an outbox table within the current business DB transaction.
2. After successful transaction commit, optionally invokes an WriterHook (fast path).
3. OutboxDispatcher executes registered EventListeners (send to MQ, update caches, call APIs, etc.).
4. On success, OutboxDispatcher updates outbox status to DONE; on failure updates to RETRY/DEAD.
5. A low-frequency OutboxPoller (optional) scans DB as fallback only (node crash, enqueue downgrade, missed enqueue) and forwards unfinished events to a handler; CDC can also serve as the fallback path.
6. Delivery semantics: **at-least-once**; duplicates are allowed and must be handled downstream by `eventId`.
7. `Outbox` composite builder (`singleNode`/`multiNode`/`ordered`) is the recommended entry point, wiring dispatcher, poller, and writer with correct defaults for each deployment topology.

## Constraints

- Core MUST NOT depend on Spring (no Spring TX, no JdbcTemplate).
- DB access MUST use standard JDBC.
- Spring integration is optional and implemented via an adapter module.

## Non-Goals

- Exactly-once end-to-end.
- Distributed transactions.

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
