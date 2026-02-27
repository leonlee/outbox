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

## Open Findings

### [M1] `OutboxPoller.markDead` catches only `SQLException`
**Severity:** Medium | **File:** `outbox-core/.../poller/OutboxPoller.java:213-220`

Same pattern as the fixed H1 issue. If `outboxStore.markDead()` throws a `RuntimeException`,
it propagates uncaught. The caller (`dispatchRow`) has a catch at line 187-190 that logs
and continues, so the impact is limited to a logged error rather than silent data corruption.
Still, the pattern should be consistent with the fix applied to `OutboxDispatcher.withConnection`.

```java
// Current
} catch (SQLException e) {
    logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
}

// Should be
} catch (SQLException | RuntimeException e) {
    logger.log(Level.SEVERE, "Failed to mark DEAD for eventId=" + eventId, e);
}
```

---

### [M2] `convertToEnvelope` doesn't reconstruct `availableAt`
**Severity:** Low | **File:** `outbox-core/.../poller/OutboxPoller.java:200-211`

When the poller converts a database row back to an `EventEnvelope`, `availableAt` is not
reconstructed from the `available_at` column (the column isn't even in the SELECT). The
dispatched envelope will have `availableAt() == null` even if the original had a delayed
delivery time. This has no correctness impact (the SQL already filtered by
`available_at <= ?`), but listeners that inspect `availableAt` for observability will see
`null` for poller-sourced events.

---

### [L1] `DefaultJsonCodec` doesn't validate lone surrogates
**Severity:** Low | **File:** `outbox-core/.../util/DefaultJsonCodec.java:159-170`

The `\uXXXX` handler parses 4 hex digits and casts to `char`. This is correct for
well-formed JSON where supplementary characters are encoded as surrogate pairs
(`\uD83D\uDE00`). However, malformed JSON with a lone surrogate (`\uD800` not followed by
a low surrogate) produces an invalid Java string. This is unlikely since headers are
typically ASCII, and the codec is only used for `Map<String, String>` headers — not
arbitrary user content.

---

### [L2] `DeadEventManager.query` and `count` return defaults on failure
**Severity:** Low | **File:** `outbox-core/.../dead/DeadEventManager.java:42-49, 111-119`

`query()` returns `List.of()` and `count()` returns `0` on database failure, which is
indistinguishable from "no dead events." Callers have no way to detect whether the
operation failed or genuinely found nothing. The errors are logged at SEVERE level, so
operators can detect failures via logs, but programmatic callers cannot.

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
