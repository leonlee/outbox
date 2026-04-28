# Code Review — outbox v0.9.3-SNAPSHOT

Full review of all modules (outbox-core, outbox-jdbc, outbox-gson, outbox-micrometer,
outbox-spring-boot-starter, outbox-spring-adapter, outbox-testing). 40+ source files,
15+ test files examined.

---

## CRITICAL (3)

### C1. NoOpConnectionProvider returns null → NPE with try-with-resources

- **File:** `outbox-testing/src/main/java/io/outbox/testing/NoOpConnectionProvider.java:16`
- **Problem:** `getConnection()` returns `null`. Java's try-with-resources always calls `.close()`
  on the resource variable. When `conn` is null, this throws `NullPointerException`.
- **Risk:** Users wrapping a `NoOpConnectionProvider`-acquired connection in
  `try (Connection conn = provider.getConnection())` crash at cleanup time.
- **Fix:** Change to `throw new UnsupportedOperationException("NoOpConnectionProvider cannot provide real connections")`.

### C2+C3. markDeferred status inconsistency between InMemory and JDBC stores

- **Files:** `AbstractJdbcOutboxStore.java:188-193` (sets RETRY), `InMemoryOutboxStore.java:77` (sets NEW)
- **Problem:** `markDeferred` (for `DispatchResult.RetryAfter`) is not a failure — the handler
  explicitly deferred delivery without penalty. Setting status=RETRY in the JDBC store polls
  monitoring queries that count `WHERE status=2` (RETRY) rows as failures, causing false alarms.
  The InMemory store correctly preserves NEW status.
- **Fix:** Change `AbstractJdbcOutboxStore.markDeferred` to use `EventStatus.NEW` instead of `RETRY`,
  aligning both implementations. The `available_at` timestamp already controls re-delivery timing.

---

## MAJOR (8)

### M1. ExponentialBackoffRetryPolicy: baseDelayMs >= maxDelayMs silently disables backoff

- **File:** `ExponentialBackoffRetryPolicy.java:38-43`
- **Problem:** When `baseDelayMs >= maxDelayMs`, integer division `maxDelayMs / baseDelayMs = 0`,
  so `shift > 0` is always true → every attempt caps at `maxDelayMs` with no exponential growth.
  No warning issued.
- **Fix:** Add `if (baseDelayMs >= maxDelayMs) throw new IllegalArgumentException(...)` in constructor.

### M2. DefaultInFlightTracker: negative clock adjustment wedges TTL entries

- **File:** `DefaultInFlightTracker.java:38-57`
- **Problem:** If NTP adjusts the system clock backward, `now - existing` becomes negative,
  so `> ttlMs` is never true. The entry is permanently stuck in the in-flight map.
- **Fix:** Guard with `now < existing || now - existing > ttlMs` (treat backward clock as expired).

### M3. Outbox.close() throws first failure; later root-cause failures buried

- **File:** `Outbox.java:102-147`
- **Problem:** The first component closure failure is thrown, with subsequent failures as suppressed
  exceptions. If `purgeScheduler.close()` fails because the DB connection was already closed (secondary),
  but `poller.close()` fails due to a stuck thread (root cause), the secondary exception is thrown
  with the root cause suppressed.
- **Fix:** Log each caught exception at `SEVERE` before accumulation, so all failures appear in logs
  regardless of which is ultimately thrown.

### M4. OutboxDispatcher.close() misleading shutdown comment

- **File:** `OutboxDispatcher.java:342-350`
- **Problem:** "poller will retry them" — this is correct because events were persisted before
  enqueuing, but the comment assumes the reader knows this invariant.
- **Fix:** Expand the comment to explain why discarding in-memory state is safe.

### M5. MicrometerMetricsExporter TOCTOU between close() and recording methods

- **File:** `MicrometerMetricsExporter.java:135-230`
- **Problem:** Recording methods check `if (closed) return;` (volatile read), then call meter
  methods. Between the check and call, `close()` could remove the meter from the registry.
  In practice, `Outbox.close()` shuts down dispatcher/poller first (stopping all recording),
  then closes metrics — so the race cannot occur under normal usage. But standalone use
  could trigger it.
- **Fix:** Document that `close()` must be called after all recording is complete.

### M6. DefaultInFlightTracker eviction threshold too conservative

- **File:** `DefaultInFlightTracker.java:59-64`
- **Problem:** Eviction runs every ~1024 acquires and only removes entries older than `2 * ttlMs`.
  Under sustained load with short TTLs, stale entries accumulate between sweeps.
- **Fix:** Reduce sampling rate from 1024 to 256; reduce eviction threshold from `2 * ttlMs` to `ttlMs`.

### M7. DispatchResult.RetryAfter accepts zero delay

- **File:** `DispatchResult.java:87-93`
- **Problem:** Zero delay produces `available_at = Instant.now()`, causing a pointless DB UPDATE
  that achieves nothing (the event is immediately available again).
- **Fix:** Add `|| delay.isZero()` to the validation check.

### M8. OutboxDispatcher Javadoc says maxAttempts >= 1, code allows 0

- **File:** `OutboxDispatcher.java:443` (Javadoc) vs `OutboxDispatcher.java:85` (validation)
- **Problem:** Javadoc states ">= 1" but code allows `>= 0`. At 0, first failure → immediate DEAD.
  Behavior is intentional and useful (fire-and-forget), but undocumented.
- **Fix:** Update Javadoc to ">= 0. Setting to 0 causes immediate DEAD on first failure."

---

## MINOR (8)

### m1. JdbcTemplate.bindParams missing Long branch

- **File:** `JdbcTemplate.java:69-84`
- **Problem:** `String`, `Integer`, `Timestamp` have explicit branches; `Long` falls through
  to generic `setObject`. No current code path passes `Long`, but it's a latent gap.
- **Fix:** Add `else if (param instanceof Long n) { ps.setLong(i + 1, n); }`.

### m2. Stale Javadoc references to io.elestyle.outbox.spi.JsonCodec

- **Files:** `JsonCodec.java:19`, `GsonJsonCodec.java:19`
- **Problem:** Package was renamed from `io.elestyle.outbox` to `io.outbox`. These two
  Javadoc references still use the old name.
- **Fix:** Replace with `io.outbox.spi.JsonCodec`.

### m3. Stale references to removed DefaultJsonCodec in planning docs

- **Files:** `CODE_REVIEW.md` (this file, previous version), `.planning/codebase/*.md`
- **Problem:** `DefaultJsonCodec` was removed and replaced with `JsonCodec` SPI + `GsonJsonCodec`.
  Planning docs still reference the removed class.
- **Fix:** N/A — historical planning docs; previous review findings obsoleted by this review.

### m4. OutboxPoller.close() 5-second timeout is a magic number

- **File:** `OutboxPoller.java:237`
- **Problem:** Hardcoded `awaitTermination(5, TimeUnit.SECONDS)` with no named constant.
- **Fix:** Extract to `TERMINATION_TIMEOUT_SECONDS` constant.

### m5. MySqlOutboxStore.claimPending transactional requirement undocumented

- **File:** `MySqlOutboxStore.java:42-79`
- **Problem:** Two-phase `SELECT FOR UPDATE` + `UPDATE` only atomic with `autoCommit=false`.
  Guaranteed by `OutboxPoller.fetchPendingRows` but not documented on the store method.
- **Fix:** Document transactional requirement in Javadoc.

### m6. DefaultInFlightTracker accepts negative TTL silently

- **File:** `DefaultInFlightTracker.java:33-35`
- **Problem:** Negative TTL behaves identically to zero (no expiry). Test at
  `DefaultInFlightTrackerTest.java:93-102` explicitly tests and expects this, but it's undocumented.
- **Fix:** Document in constructor Javadoc.

### m7. OutboxDispatcher.close() lacks idempotency guard

- **File:** `OutboxDispatcher.java:326-351`
- **Problem:** Repeated `close()` calls execute `shutdown()`, `shutdownNow()`, and logging again.
  While executor pools tolerate redundant calls, an explicit guard is clearer.
- **Fix:** Add `AtomicBoolean closed` guard at the top of `close()`.

### m8. Outbox.start() volatile pattern undocumented

- **File:** `Outbox.java:80-84`
- **Problem:** `started` is volatile with a read-then-write pattern. Safe in practice because
  `poller.start()` is `synchronized` and idempotent, but the intent isn't documented.
- **Fix:** Document thread-safety guarantee in method Javadoc.

---

## NIT (4)

### n1. workerCount=0 creates unused CachedThreadPool

- **File:** `OutboxDispatcher.java:107`
- **Problem:** `Executors.newCachedThreadPool(DaemonThreadFactory)` is created when
  `workerCount=0`, though no tasks are ever submitted. Threads only spawn on demand,
  but the pool object is still allocated.
- **Fix:** Add comment explaining the cached pool choice (safe no-op for testing).

### n2. Redundant baseDelayMs != 0 check

- **File:** `ExponentialBackoffRetryPolicy.java:41`
- **Problem:** Constructor rejects `baseDelayMs <= 0`, so `baseDelayMs != 0` is always true.
- **Fix:** Remove the redundant check.

### n3. Gauge field references only used in close()

- **File:** `MicrometerMetricsExporter.java:57-59`
- **Problem:** `hotDepthGauge`, `coldDepthGauge`, `lagGauge` fields are stored only for
  `close()` cleanup; gauge registration uses method references to the `Atomic*` fields directly.
- **Fix:** Add comment noting retention is for cleanup removal only.

### n4. DaemonThreadFactory counter grows unbounded

- **File:** `DaemonThreadFactory.java:23`
- **Problem:** The `AtomicInteger` counter never resets. Across many `Outbox` instance
  lifecycles, thread names grow arbitrarily large. Cosmetic only.
- **Fix:** Not required for correctness; left as-is.

---

## Pre-existing review findings (from prior CODE_REVIEW.md)

The previous CODE_REVIEW.md findings (C2, H1-H3, M1-M2, L1-L2) were all fixed and
verified. This review replaces those findings with the current state.

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 3 |
| Major    | 8 |
| Minor    | 8 |
| Nit      | 4 |
| **Total**| **23** |
