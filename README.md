[![CI](https://github.com/leonlee/outbox/actions/workflows/ci.yml/badge.svg)](https://github.com/leonlee/outbox/actions/workflows/ci.yml)
# outbox-java

Minimal, Spring-free outbox framework with JDBC persistence, optional hot-path enqueue, and poller/CDC fallback.

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
