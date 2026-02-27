# Code Review

Full code review of the outbox framework. Last updated: 2026-02-27 (v0.8.4-SNAPSHOT).

## Fixed Issues

Issues identified and fixed in commit `02fe82e`:

### [C2] Missing `spring-boot-configuration-processor` — Fixed
**Severity:** Critical | **File:** `outbox-spring-boot-starter/pom.xml`

The starter module had no `spring-boot-configuration-processor` dependency, so no
`spring-configuration-metadata.json` was generated. Users got zero IDE auto-completion
for `outbox.*` properties.

**Fix:** Added as `<optional>true</optional>` dependency.

---

### [H1] `withConnection` catches only `SQLException` — Fixed
**Severity:** High | **File:** `outbox-core/.../dispatch/OutboxDispatcher.java:296-303`

`withConnection` only caught `SQLException`. If a store method threw `RuntimeException`
(e.g. `OutboxStoreException`), it propagated into `dispatchEvent`'s catch block, causing
`handleFailure` to run on a **successfully-processed** event — potentially re-delivering
or marking DEAD an event whose listener already succeeded.

**Fix:** Broadened catch to `SQLException | RuntimeException`.

---

### [H2] `WriterOnlyBuilder` silently accepts irrelevant config — Fixed
**Severity:** High | **File:** `outbox-core/.../Outbox.java` (WriterOnlyBuilder)

`WriterOnlyBuilder` inherited `listenerRegistry()`, `interceptor()`, `interceptors()`,
`jsonCodec()`, `intervalMs()`, `batchSize()`, `skipRecent()`, `drainTimeoutMs()` from
`AbstractBuilder` — all silently ignored at build time. Users could misconfigure without
any feedback.

**Fix:** Each irrelevant method overridden to throw `UnsupportedOperationException`.
`metrics()` intentionally kept (see H3).

---

### [H3] `WriterOnlyBuilder.build()` discards `MetricsExporter` — Fixed
**Severity:** High | **Files:** `outbox-core/.../Outbox.java:797`, `outbox-spring-boot-starter/.../OutboxAutoConfiguration.java:185-198`

`WriterOnlyBuilder.build()` passed `null` for metrics to the `Outbox` constructor, so
`Outbox.close()` never called `metrics.close()` — leaking Micrometer meters. Auto-config
also never wired metrics in `WRITER_ONLY` mode.

**Fix:** Pass `metrics` field instead of `null` in `build()`. Wire `builder.metrics(metrics)`
in auto-config's `WRITER_ONLY` case.

---

### [M1] `OutboxPoller.markDead` catches only `SQLException` — Fixed
**Severity:** Medium | **File:** `outbox-core/.../poller/OutboxPoller.java:213-220`

Same pattern as H1. Broadened catch to `SQLException | RuntimeException`.

---

### [M2] `convertToEnvelope` doesn't reconstruct `availableAt` — Fixed
**Severity:** Low | **File:** `outbox-core/.../poller/OutboxPoller.java:200-211`

Added `available_at` to all SELECT queries (`pollPending`, `selectClaimed`, `queryDead`,
PostgreSQL `RETURNING`), added `availableAt` field to `OutboxEvent` record, and set it on
the reconstructed `EventEnvelope` in `convertToEnvelope`.

---

### [L1] `DefaultJsonCodec` doesn't validate lone surrogates — Fixed
**Severity:** Low | **File:** `outbox-core/.../util/DefaultJsonCodec.java:159-170`

Added surrogate pair validation: high surrogates must be followed by `\uDC00-\uDFFF`,
lone low surrogates are rejected.

---

### [L2] `DeadEventManager` returns defaults on failure — Fixed
**Severity:** Low | **File:** `outbox-core/.../dead/DeadEventManager.java`

Changed from swallowing exceptions (returning `List.of()` / `0` / `false`) to wrapping
`SQLException` in `RuntimeException` and letting `RuntimeException` propagate. Callers
can now distinguish database failures from empty results.

---

## Reviewed and Verified (No Issues)

Areas specifically checked and found to be correct:

- **SQL injection prevention:** All queries use parameterized statements. `TableNames.validate()`
  enforces `[a-zA-Z_][a-zA-Z0-9_]*` for table names used in string concatenation.
- **Connection lifecycle:** `try-with-resources` used consistently across `OutboxPoller`,
  `OutboxDispatcher.withConnection`, `DeadEventManager`, and `OutboxPurgeScheduler`.
  `OutboxPoller.fetchPendingRows` properly rolls back on failure within try-with-resources.
- **Thread safety:** `AtomicBoolean`/`AtomicInteger` for flags, `volatile` for visibility,
  `ConcurrentHashMap` in `DefaultListenerRegistry`, `synchronized` on `OutboxPoller` lifecycle
  methods. `OutboxDispatcher.pollCounter` overflow handled correctly via `& 0x7FFFFFFF` bitmask.
- **Shutdown ordering:** `Outbox.close()` shuts down purgeScheduler → poller → dispatcher → metrics.
  This is correct: the poller should stop producing before the dispatcher stops consuming, and
  metrics should be closed last so shutdown activity is still recorded.
- **Queue draining on shutdown:** `OutboxDispatcher.close()` clears in-memory queues after workers
  stop. Events are already persisted in the database, so the poller will re-fetch them on
  restart. The "stranded" log message confirms this is by design.
- **Fair queue distribution:** The `pollCounter % 3 == 2` pattern provides stable 2:1 hot:cold
  ratio even across int overflow, thanks to the sign-bit mask.
- **Retry policy overflow:** `ExponentialBackoffRetryPolicy` caps at `attempts >= 31`, guards
  against multiplication overflow, and applies `Math.min(maxDelayMs, ...)` as final bound.
- **WriterHook suppression:** `OutboxWriter.writeAll()` returns empty list when
  `beforeWrite` returns null/empty. This is the documented contract — hooks that
  intentionally suppress writes should not generate warnings.
- **Spring auto-config:** All four mode branches properly validate required fields via the
  builder's own `validateRequired()`. No configuration can slip through without validation.
- **MicrometerMetricsExporter.close():** The `volatile boolean closed` guard is sufficient.
  Worst case is a harmless metric recorded between `closed = true` and meter removal.
  Micrometer's `MeterRegistry` is itself thread-safe.
