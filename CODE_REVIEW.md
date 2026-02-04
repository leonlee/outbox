# Code Review Notes

Date: 2026-02-04

## Findings (Fixed)

1. **Poller could silently stop on unexpected runtime exceptions**
   - **Where**: `outbox-core/src/main/java/outbox/core/poller/OutboxPoller.java`
   - **Issue**: `ScheduledExecutorService` suppresses future runs if a task throws. `runOnce()` only caught `SQLException`, so JSON decode or other runtime errors could terminate polling permanently.
   - **Fix**: Wrapped the full `runOnce()` in a broad try/catch and added per-row decode handling. Malformed rows are marked `DEAD` with a log entry.

2. **Hot-path enqueue could throw after commit**
   - **Where**: `outbox-core/src/main/java/outbox/core/client/DefaultOutboxClient.java`
   - **Issue**: `dispatcher.enqueueHot()` could throw (e.g., dispatcher shutdown) inside `afterCommit`, which contradicts the spec's "must not throw" downgrade behavior.
   - **Fix**: Wrapped enqueue in try/catch; on exception, log and count as a drop.

3. **Constructor parameter validation missing for dispatcher**
   - **Where**: `outbox-core/src/main/java/outbox/core/dispatch/Dispatcher.java`
   - **Issue**: Negative worker counts or non-positive queue capacities could pass through to `ArrayBlockingQueue`/`Executors` and fail later with unclear errors.
   - **Fix**: Added argument validation for `maxAttempts`, `workerCount`, and queue capacities. Kept `workerCount=0` as a supported "no workers" mode.

4. **Poller accepted invalid scheduling arguments**
   - **Where**: `outbox-core/src/main/java/outbox/core/poller/OutboxPoller.java`
   - **Issue**: `batchSize <= 0`, `intervalMs <= 0`, or negative `skipRecent` would throw late (scheduler) or behave incorrectly.
   - **Fix**: Added constructor validation for batch size, interval, and skipRecent.

5. **Infinite loop risk in DefaultInFlightTracker.tryAcquire()**
   - **Where**: `outbox-core/src/main/java/outbox/core/dispatch/DefaultInFlightTracker.java`
   - **Issue**: CAS retry loop had no backoff; could spin indefinitely under high contention.
   - **Fix**: Added maximum attempt limit (10) and Thread.yield() for backoff.

6. **Race condition in OutboxPoller.start()**
   - **Where**: `outbox-core/src/main/java/outbox/core/poller/OutboxPoller.java`
   - **Issue**: `scheduled` field was not volatile; multiple pollers could start concurrently.
   - **Fix**: Made field volatile and added synchronized block to start().

7. **No thread-safe shutdown in OutboxPoller**
   - **Where**: `outbox-core/src/main/java/outbox/core/poller/OutboxPoller.java`
   - **Issue**: `close()` didn't wait for graceful shutdown.
   - **Fix**: Added awaitTermination(5s) after shutdownNow().

8. **Mutable lists from registry lookups**
   - **Where**: `outbox-core/src/main/java/outbox/core/registry/DefaultPublisherRegistry.java` and `DefaultHandlerRegistry.java`
   - **Issue**: Callers could modify returned lists, affecting subsequent lookups.
   - **Fix**: Return Collections.unmodifiableList().

9. **No payload size validation in EventEnvelope**
   - **Where**: `outbox-core/src/main/java/outbox/core/api/EventEnvelope.java`
   - **Issue**: No bounds checking on payload size; could exhaust memory or violate DB constraints.
   - **Fix**: Added 1MB payload limit with MAX_PAYLOAD_BYTES constant.

10. **Unbounded error string in last_error column**
    - **Where**: `outbox-jdbc/src/main/java/outbox/jdbc/JdbcOutboxRepository.java`
    - **Issue**: Error string passed directly to DB without truncation.
    - **Fix**: Truncate to 4000 characters.

11. **Callback exception swallowing in JdbcTransactionManager**
    - **Where**: `outbox-jdbc/src/main/java/outbox/jdbc/JdbcTransactionManager.java`
    - **Issue**: `finalizeTx()` caught and suppressed exceptions from `clearAfterCommit()`, masking event publishing failures.
    - **Fix**: Propagate callback exceptions after cleanup completes.

## Additional Review Passes (API Ergonomics / Concurrency / Perf)

- **API Ergonomics**
  - `OutboxMetrics.NOOP` is a simple default but the interface doesn't document threading guarantees; consumers should assume callbacks may be concurrent.
  - The core doesn't expose a builder or factory for `Dispatcher`/`OutboxPoller`; wiring is explicit but verbose. Acceptable for a low-level core.

- **Concurrency Edge-Cases**
  - Dispatcher uses hot-queue priority and may starve cold queue under sustained hot load. This matches the spec but should be documented for operators.
  - In-flight dedupe is in-memory only; a process crash clears it. This is consistent with at-least-once semantics.

- **Performance**
  - `DefaultPublisherRegistry` and `DefaultHandlerRegistry` allocate lists on each lookup; acceptable at this scale but could be optimized if lookup is a hot path.
  - `JsonCodec` is intentionally minimal and avoids external dependencies; it's faster but limited to flat string maps.

## Residual Risks / Assumptions

- `JsonCodec` is intentionally minimal and expects headers to be a flat string map. Malformed JSON is now quarantined to `DEAD`, but the framework does not attempt recovery for malformed rows.
- Status update failures (e.g., DB outage when marking `DONE`) can lead to duplicate delivery later. This matches at-least-once semantics but should be accounted for downstream.

## Recommendations (Optional)

- Add an explicit metric for poller decode failures and `DEAD` transitions due to malformed rows.
- Consider persisting a full stack trace in `last_error` if the DB column can accommodate it.
