# Codebase Concerns

**Analysis Date:** 2026-02-19

## Queue Overflow & Event Loss

**Event loss via hot queue rejection:**

- Issue: When `DispatcherWriterHook.afterCommit` attempts to enqueue events to the hot queue and the queue is full,
  events are dropped with only a warning log. The dropped events rely solely on the poller fallback for eventual
  delivery.
- Files: `outbox-core/src/main/java/outbox/dispatch/DispatcherWriterHook.java` (lines 40-51),
  `outbox-core/src/main/java/outbox/dispatch/OutboxDispatcher.java` (lines 119-138)
- Impact: High throughput bursts or undersized queues (default 1000 each) can silently drop events from the hot path.
  While the poller will recover them, this defeats the performance optimization of the hot path.
- Fix approach: Monitor `incrementHotDropped()` metrics closely. Tune `hotQueueCapacity()` and `workerCount()` based on
  throughput SLA. Consider alerting on high drop rates. Document queue capacity requirements relative to expected
  throughput.

**Cold queue overflow during polling:**

- Issue: When the poller calls `handler.handle()` to enqueue events to the cold queue and it returns `false` (queue
  full), the poller breaks out of the batch loop early. Those unprocessed rows remain PENDING in the database but won't
  be attempted again until the next poll cycle (default 5s).
- Files: `outbox-core/src/main/java/outbox/poller/OutboxPoller.java` (lines 154-162)
- Impact: Under sustained high load with slow dispatcher, events can experience unbounded delays between polls. If the
  cold queue consistently fills, polling throughput degrades.
- Fix approach: Monitor `coldQueueRemainingCapacity()` and adjust `coldQueueCapacity()` upward or reduce `batchSize()`
  to match cold queue draining speed. Consider increasing `workerCount()` to drain cold queue faster. Add alerts when
  cold queue utilization exceeds 80%.

## Timestamp Precision & Claim Locking Race Conditions

**Nanosecond precision loss in timestamp comparisons:**

- Issue: Database timestamps (H2, MySQL, PostgreSQL) may truncate nanoseconds to milliseconds or lower precision. During
  claim-based locking, the poller compares `locked_at` timestamps in the Phase-2 SELECT to determine if a lock has
  expired. If `now.truncatedTo(ChronoUnit.MILLIS)` is not performed consistently, a race condition can cause stale locks
  to be missed.
- Files: `outbox-jdbc/src/main/java/outbox/jdbc/store/MySqlOutboxStore.java` (line 60),
  `outbox-jdbc/src/main/java/outbox/jdbc/store/PostgresOutboxStore.java`,
  `outbox-core/src/main/java/outbox/poller/OutboxPoller.java` (line 119)
- Impact: In multi-node deployments with `claimLocking()` enabled, a lock that was set with nanoseconds may not match
  the millisecond-truncated comparison in the database. This can cause lock expiry checks to fail, leaving events locked
  indefinitely.
- Fix approach: Ensure `OutboxPoller.poll()` truncates `Instant.now()` to milliseconds before passing to
  `claimPending()`. Add integration tests that verify lock expiry timing across all database dialects at millisecond
  precision boundaries. Document this requirement in CLAUDE.md.

## Memory Leak in DefaultInFlightTracker

**Unbounded eviction interval with high throughput:**

- Issue: `DefaultInFlightTracker.maybeEvictExpired()` only evicts stale entries every ~1000 acquires (sampling via
  bitwise AND `0x3FF`). With very high throughput (e.g., 10k+ events/sec) but low TTL (e.g., 100ms), eviction may never
  run frequently enough, causing memory to grow unbounded.
- Files: `outbox-core/src/main/java/outbox/dispatch/DefaultInFlightTracker.java` (lines 57-62)
- Impact: Long-running dispatchers with high throughput can experience out-of-memory errors if TTL is set but the
  eviction interval is too sparse. Workers may fail silently.
- Fix approach: If using `DefaultInFlightTracker(ttlMs)`, monitor heap usage in production. Consider reducing TTL or
  using an external cache with automatic expiry (e.g., Caffeine). Document that TTL is optional and disabled by default;
  only enable if workers can hang indefinitely and need recovery.

## Exception Handling & Callback Resilience

**Swallowed exceptions in WriterHook lifecycle:**

- Issue: In `OutboxWriter.writeAll()`, exceptions thrown by `WriterHook.afterWrite()`, `afterCommit()`, and
  `afterRollback()` are caught and logged at WARNING level but not re-thrown. This means application code cannot react
  to hook failures.
- Files: `outbox-core/src/main/java/outbox/OutboxWriter.java` (lines 145-151)
- Impact: If a critical `afterCommit` hook fails (e.g., a message publisher integration), the application won't know
  about it. The transaction will have committed successfully, but downstream processing may be incomplete.
- Fix approach: Document this behavior explicitly. Recommend that hook implementations be defensive and fail-safe.
  Consider adding a `WriterHook.onError(Exception)` callback for failures that need application-level handling. Log at
  SEVERE level (not WARNING) if hook failures could indicate data loss.

**Swallowed exceptions in EventInterceptor.afterDispatch():**

- Issue: In `OutboxDispatcher.runAfterDispatch()`, exceptions from `EventInterceptor.afterDispatch()` are caught and
  logged but not propagated, even if they occur before the event is marked DONE.
- Files: `outbox-core/src/main/java/outbox/dispatch/OutboxDispatcher.java` (lines 223-231)
- Impact: If an interceptor fails during cleanup (e.g., audit logging), the event is still marked DONE even though the
  interceptor's contract was violated. Downstream systems may not see complete audit trails.
- Fix approach: Consider propagating interceptor exceptions at SEVERE level with event ID for audit purposes. Allow
  callers to implement stricter behavior via a custom `RetryPolicy` or interceptor registration order.

**Exceptions in DeadEventManager.replayAll() suppress later errors:**

- Issue: In `DeadEventManager.replayAll()`, if an exception occurs mid-batch, `batch.isEmpty()` check may not detect
  all-failed batches correctly, potentially entering an infinite loop if the batch query succeeds but all replays fail.
- Files: `outbox-core/src/main/java/outbox/dead/DeadEventManager.java` (lines 76-103)
- Impact: Replay operations against a large number of DEAD events can hang indefinitely if there's a transient failure
  affecting all events (e.g., all events fail listener lookup).
- Fix approach: Add a maximum retry count or timeout to `replayAll()`. Track consecutive failures and break after a
  threshold. Add integration tests for partial batch failure scenarios.

## Database-Specific Gotchas

**H2 does not support DELETE...ORDER BY...LIMIT:**

- Issue: H2 database (even in MySQL compatibility mode) does not support `DELETE ... ORDER BY ... LIMIT` syntax. The
  codebase uses subquery-based deletion for `H2EventPurger` and `H2AgeBasedPurger`, but this pattern must be maintained
  for all H2 queries.
- Files: `outbox-jdbc/src/main/java/outbox/jdbc/purge/H2EventPurger.java`,
  `outbox-jdbc/src/main/java/outbox/jdbc/purge/H2AgeBasedPurger.java`
- Impact: If a developer adds new purge logic or claim logic for H2 without using the subquery pattern, queries will
  fail at runtime with a syntax error.
- Fix approach: Add a comment in `AbstractJdbcOutboxStore` documenting H2's limitation. Add a test case that explicitly
  verifies H2 purge logic runs without errors. Consider extracting a helper method for the subquery pattern to reduce
  duplication.

**MySQL timestamp precision in claim phase:**

- Issue: MySQL's `TIMESTAMP` column type may have sub-millisecond precision depending on version and configuration. When
  comparing `locked_at` in the claim Phase-2 SELECT, the row mapper uses `Timestamp.toInstant()` which includes whatever
  precision MySQL returned. This can cause claims to fail if the comparison was done at a coarser precision.
- Files: `outbox-jdbc/src/main/java/outbox/jdbc/store/AbstractJdbcOutboxStore.java` (lines 39-48, row mapper),
  `outbox-jdbc/src/main/java/outbox/jdbc/store/MySqlOutboxStore.java` (line 60)
- Impact: Claim-based locking can experience false positives (thinking a lock is still active when it's not) or false
  negatives (thinking a lock is expired when it's not), leading to event duplication or starvation in multi-node setups.
- Fix approach: Enforce millisecond truncation at both write time (already done in `MySqlOutboxStore.claimPending()`)
  and read time in the `EVENT_ROW_MAPPER`. Add a test that verifies claim expiry at millisecond boundaries works
  consistently across H2, MySQL, and PostgreSQL.

## Large File Complexity

**Outbox.java (773 lines) — Multiple sealed builder subclasses:**

- Issue: The `Outbox` composite builder uses sealed `AbstractBuilder<B>` with four subclasses (`SingleNodeBuilder`,
  `MultiNodeBuilder`, `OrderedBuilder`, `WriterOnlyBuilder`), each with unique builder methods. This creates a large
  file with high cognitive complexity.
- Files: `outbox-core/src/main/java/outbox/Outbox.java`
- Impact: Difficult to navigate and understand which builder methods are available for each scenario. Risk of
  misconfiguration (e.g., calling `claimLocking()` on `SingleNodeBuilder` instead of `MultiNodeBuilder`).
- Fix approach: Consider extracting each builder subclass to its own file (e.g., `SingleNodeBuilder.java`). The sealed
  class syntax can still reference them via imports. Document which builder is appropriate for each deployment topology
  in the class javadoc.

**AbstractJdbcOutboxStore.java (292 lines) — Multiple sub-packages for variants:**

- Issue: The store hierarchy spans multiple files (`store/`, `purge/`, `purge/` with age-based variants), with shared
  SQL and row mappers. Code duplication exists between `AbstractJdbcEventPurger` and `AbstractJdbcAgeBasedPurger`.
- Files: `outbox-jdbc/src/main/java/outbox/jdbc/store/`, `outbox-jdbc/src/main/java/outbox/jdbc/purge/`
- Impact: Changes to purge SQL logic must be duplicated across two hierarchies. Tests for purge behavior are split
  across `JdbcEventPurgerTest` and `AgeBasedPurgerTest`.
- Fix approach: Extract a shared abstract base for purge operations (e.g., `AbstractJdbcPurger`) with a template method
  for the deletion query. Document why two hierarchies are needed (status-based vs. age-based have different semantics).

## Performance Scaling Limits

**Fair queue draining ratio is hardcoded:**

- Issue: The 2:1 hot/cold queue draining ratio is hardcoded in `OutboxDispatcher.pollFairly()` via the modulo `3`
  calculation. This ratio cannot be tuned per deployment.
- Files: `outbox-core/src/main/java/outbox/dispatch/OutboxDispatcher.java` (lines 144-161)
- Impact: If an application has extremely unbalanced hot/cold event rates (e.g., 90% hot, 10% cold), the fixed ratio may
  not be optimal. Tuning requires code changes.
- Fix approach: Add an optional `fairnessRatio()` builder method to `OutboxDispatcher.Builder` (default 2:1). Document
  the ratio's effect on SLA. Consider adding a metric for hot vs. cold event throughput to help operators tune this
  value.

**Poller batch size doesn't account for listener capacity:**

- Issue: `OutboxPoller.poll()` calculates `effectiveBatch` as the minimum of `batchSize` and
  `handler.availableCapacity()`, but if the handler's capacity is very small (e.g., 10), the poller will fetch only 10
  events per cycle. With a 5-second interval, throughput is capped at 2 events/sec.
- Files: `outbox-core/src/main/java/outbox/poller/OutboxPoller.java` (lines 136-140)
- Impact: Scaling becomes difficult if the cold queue is undersized. Operators must choose between frequent polls (high
  CPU) or larger batches (risking queue overflow).
- Fix approach: Document the relationship between `batchSize`, `coldQueueCapacity`, `intervalMs`, and achievable
  throughput. Provide a tuning guide. Consider adaptive polling that increases frequency if the queue is under-utilized.

**In-flight tracker contention at high throughput:**

- Issue: `DefaultInFlightTracker.tryAcquire()` uses a `ConcurrentHashMap` and retries up to 10 times on CAS failures. At
  very high throughput (10k+ events/sec with TTL enabled), contention on frequently-timing-out entries could cause
  busy-spinning.
- Files: `outbox-core/src/main/java/outbox/dispatch/DefaultInFlightTracker.java` (lines 36-55)
- Impact: CPU usage may spike unnecessarily; threads retry acquisition without backoff.
- Fix approach: If high contention is observed in production, consider adding exponential backoff to retries or using a
  lock-free data structure designed for high throughput (e.g., Caffeine with manual expiry). Profile contention with a
  tool like JFR before optimizing.

## Fragile Test Expectations

**Jitter-based retry timing in tests:**

- Issue: `ExponentialBackoffRetryPolicy` uses jitter `[0.5, 1.5)` to avoid thundering herd. Tests that assert exact
  retry delays will be flaky.
- Files: Tests using `ExponentialBackoffRetryPolicy` (search for retry timing assertions in `OutboxDispatcherTest.java`,
  `OutboxPollerTest.java`)
- Impact: Tests may pass locally but fail intermittently in CI, making it hard to diagnose real issues.
- Fix approach: Always use range assertions when testing retry delays (e.g., `assertThat(delay).isBetween(min, max)`
  rather than `assertEquals(delay, expected)`). Document this in the testing guide.

**H2 in-memory database is not transactional by default:**

- Issue: H2 in-memory mode may not enforce full ACID semantics under certain configurations. Test assertions about
  transaction rollback behavior may not catch real issues that would occur with production databases.
- Files: All `*Test.java` files using H2 in-memory database
- Impact: Tests pass locally but fail in production with MySQL or PostgreSQL due to transactional boundary differences.
- Fix approach: Run a subset of integration tests against real database containers (MySQL, PostgreSQL) in CI. Document
  which test classes require production-database validation.

## Missing Error Recovery

**Dispatcher loop catches Throwable but logs without structured context:**

- Issue: In `OutboxDispatcher.workerLoop()`, a `Throwable` is caught and logged, but no event ID or queue state is
  included in the log message. If the error is related to a specific event, it's hard to debug.
- Files: `outbox-core/src/main/java/outbox/dispatch/OutboxDispatcher.java` (lines 176-180)
- Impact: Production support engineers cannot correlate dispatcher errors to specific events without manual log parsing.
- Fix approach: Add event ID to the log context (e.g., MDC in a logging framework). Consider emitting an error metric
  with the exception type for alerting.

**Poller poll() cycle swallows all exceptions uniformly:**

- Issue: `OutboxPoller.poll()` catches `Throwable` and logs it, but network errors, constraint violations, and
  programming errors are treated the same way. No distinction is made for retry-able vs. permanent failures.
- Files: `outbox-core/src/main/java/outbox/poller/OutboxPoller.java` (lines 131-133)
- Impact: Transient database connection failures are logged at SEVERE but not retried, potentially missing a recovery
  opportunity.
- Fix approach: Distinguish between transient errors (SQL state 08xxx, timeout) and permanent errors (constraint
  violations, syntax errors). Log transient errors at WARNING; permanent errors at SEVERE. Consider allowing a custom
  exception handler.

## Documentation Gaps

**Hot queue rejection is silent in non-metric-aware deployments:**

- Issue: If `MetricsExporter.NOOP` is used (the default), dropped events are only indicated by a WARNING log. Operators
  without structured logging may miss this.
- Files: `outbox-core/src/main/java/outbox/dispatch/DispatcherWriterHook.java` (line 45)
- Impact: Silent event loss in production if monitoring is not explicitly configured.
- Fix approach: Recommend using `MicrometerMetricsExporter` or a custom exporter in all non-testing deployments. Add a
  startup warning if metrics are NOOP and workerCount > 0.

**Database deadlock recovery is undocumented:**

- Issue: If a database deadlock occurs during `markDone()`, `markRetry()`, or `markDead()`, the error is logged at
  SEVERE but there's no retry mechanism. The event remains in an inconsistent state (processed but not marked).
- Files: `outbox-core/src/main/java/outbox/dispatch/OutboxDispatcher.java` (lines 270-277)
- Impact: In high-concurrency setups with aggressive event processing, deadlocks could leave events unacknowledged.
- Fix approach: Document the expectation that the database is configured with sufficient `innodb_lock_wait_timeout` (
  MySQL). Consider adding a retry with exponential backoff for transient SQL errors. Add deadlock recovery tests.

---

*Concerns audit: 2026-02-19*
