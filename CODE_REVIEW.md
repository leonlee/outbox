# Code Review — outbox 0.9.3-SNAPSHOT

Full review of all modules (outbox-core, outbox-jdbc, outbox-gson, outbox-micrometer,
outbox-spring-boot-starter, outbox-spring-adapter, outbox-testing).

The first attempt at these fixes (#55) was itself reviewed twice before merging; several of its
fixes were wrong and are corrected below. The fixes were then re-applied on top of the
duplicate-dispatch work in #56, which had rewritten some of the same code. Every entry ends with
its **Outcome**; the follow-up findings are listed separately at the end.

---

## CRITICAL (3)

### C1. NoOpConnectionProvider returns null

- **File:** `outbox-testing/.../NoOpConnectionProvider.java`
- **Problem:** `getConnection()` returns `null`. Not because of try-with-resources — that skips
  `close()` for a null resource (JLS 14.20.3) — but because the dispatcher and poller call
  `conn.setAutoCommit(...)` on it. The NPE is caught and logged by the status-update helper, so
  with `InMemoryOutboxStore`, the fixture's documented partner, every status update silently fails
  and events never reach DONE.
- **Outcome:** Fixed. It now returns a connection proxy whose every method does nothing.
  The first attempt made it throw and then deleted it; both broke the documented use, and deleting
  it removed a public class from a published artifact.

### C2+C3. markDeferred status differs between the JDBC and in-memory stores

- **Files:** `AbstractJdbcOutboxStore.markDeferred` (wrote RETRY), `InMemoryOutboxStore.markDeferred` (writes NEW)
- **Problem:** A deferral (`DispatchResult.RetryAfter`) is not a failure, but the JDBC store marked
  it RETRY, so monitoring that counts RETRY rows reads deferrals as failures. SPEC §8.2 already
  specified `status = 0`.
- **Outcome:** Fixed. JDBC writes NEW. The in-memory store now also matches JDBC in refusing
  DONE/DEAD rows (it used to revive them, so the in-memory poller redelivered settled events) and in
  keeping `last_error`.

---

## MAJOR (8)

### M1. ExponentialBackoffRetryPolicy with baseDelayMs ≥ maxDelayMs

- **Problem:** Every attempt is then capped at `maxDelayMs` (still jittered) — effectively a fixed
  delay. That is a coherent configuration, not a malfunction.
- **Outcome:** Won't fix. The proposed constructor check rejected configurations that work today,
  and because the Spring Boot starter builds the policy in every mode, it would have failed
  application startup — including in writer-only and ordered mode, which never retry.

### M2. DefaultInFlightTracker expiry uses the wall clock

- **Problem:** A backward clock adjustment makes `now - at` small or negative, delaying expiry.
- **Outcome:** Deferred. The delay is bounded by the size of the adjustment, not permanent, and the
  tracker was rewritten in #56 (token-scoped entries, settled markers). Revisit with monotonic time
  if it matters in practice.

### M3. Outbox.close() reports only the first component failure

- **Outcome:** Fixed. Each component failure is logged at SEVERE before the first is rethrown with
  the rest suppressed. `close()` is also idempotent and serialised — see F2.

### M4. OutboxDispatcher shutdown comment

- **Problem:** "poller will retry them" omits that in multi-node mode a claimed row waits out its
  lock timeout first. (The first attempt's rewrite claimed immediate redelivery, which was worse.)
- **Outcome:** Fixed. The comment and the shutdown log both say so.

### M5. MicrometerMetricsExporter close() racing recording

- **Outcome:** Fixed (documentation). `close()` must follow the end of recording;
  `Outbox.close()` guarantees that by closing the exporter last.

### M6. DefaultInFlightTracker eviction threshold

- **Problem:** The background sweep runs every ~1024 acquires and removes entries older than
  `2 × ttlMs`, so stale entries linger between sweeps.
- **Outcome:** Won't fix. The sweep only bounds memory; correctness never depends on it, because
  every acquire checks expiry itself and reclaims an expired entry on the spot. Changing the cadence
  or threshold trades memory for sweep work, and no memory problem has been observed.

### M7. DispatchResult.RetryAfter accepts zero delay

- **Outcome:** Won't fix. A zero delay is valid and consistent with `RetryAfterException`. (The
  first attempt added the check and then reverted it.)

### M8. maxAttempts Javadoc said ≥ 1, code allows 0

- **Outcome:** Fixed. Javadoc says ≥ 0, with 0 marking an event DEAD on its first failure.

---

## MINOR (8)

| ID | Finding | Outcome |
|----|---------|---------|
| m1 | `JdbcTemplate.bindParams` had no `Long` branch | Fixed |
| m2 | Javadoc referenced `io.elestyle.outbox.spi.JsonCodec` | Fixed in #56 |
| m3 | Planning docs referenced the removed `DefaultJsonCodec` | N/A — historical |
| m4 | `OutboxPoller.close()` timeout was a magic number | Fixed (`TERMINATION_TIMEOUT_SECONDS`) |
| m5 | `MySqlOutboxStore.claimPending` transaction requirement undocumented | Fixed |
| m6 | Negative tracker TTL undocumented | Fixed — constructor Javadoc: zero or negative disables expiry |
| m7 | `OutboxDispatcher.close()` had no idempotency guard | Fixed — see F2 for why a CAS guard was wrong |
| m8 | `Outbox.start()` thread-safety undocumented | Fixed — `synchronized`; Javadoc says it is not restartable |

## NIT (4)

| ID | Finding | Outcome |
|----|---------|---------|
| n1 | `workerCount=0` cached pool unexplained | Fixed (comment) |
| n2 | Redundant `baseDelayMs != 0` check | Fixed |
| n3 | Gauge fields kept only for `close()` | Fixed (comment) |
| n4 | `DaemonThreadFactory` counter never resets | Won't fix — cosmetic |

---

## Follow-up findings (reviews of the first attempt)

| ID | Finding | Outcome |
|----|---------|---------|
| F1 | The M1 check failed Spring Boot startup for configurations that work today | Resolved by not adopting M1 |
| F2 | A CAS `close()` guard let an overlapping caller return while the first was still draining, so it could tear down the `DataSource` under running workers | `close()` is `synchronized` in `Outbox` and `OutboxDispatcher`; an overlapping call waits |
| F3 | `ObjectProvider.getIfAvailable()` threw on several `JsonCodec` beans, failing startup | Registration uses `getIfUnique()` and warns instead |
| F4 | A custom `JsonCodec` was registered only inside the auto-configured `Outbox`, so defining your own `Outbox` lost it | Dedicated `SmartInitializingSingleton` registrar |
| F5 | `start()` Javadoc claimed it was safe after `close()`; the poller throws | Javadoc corrected; behaviour kept (an existing test relies on it) |
| F6 | `InFlightTracker.release` could remove a newer owner's entry | Fixed in #56 (token-scoped release) |
| F7 | A failing `rollback()` replaced the claim failure | Claim failure kept, rollback failure attached as suppressed |
| F8 | `fetchPendingRows` re-read capacity; an empty batch from that zeroed the lag gauge | Capacity read once per poll and passed in |
| F9 | No tests covered the behaviour changes | Every fix above has a test that fails without it |
| F10 | This file contradicted the code | This revision |
| F11 | Stale-marker cleanup could delete a *newer* settled marker if its database call returned after the event was re-claimed and settled again, letting a late copy run twice | The marker's token is taken before the call; `releaseSettled(id, token)` removes only that marker |
| F12 | The codec registrar ran only after every singleton existed, too late for a bean that writes during its own initialisation | Also registered from the auto-configured store and outbox factories, so before any writer can run |
| F13 | `InMemoryOutboxStore.markDeferred` read, checked and wrote separately, so a concurrent `markDone` could be overwritten with NEW | Done in one atomic `computeIfPresent` |
| F14 | `start()` Javadoc said the poller throws after `close()`; it returns early if already started | Javadoc states both cases |

---

## Summary

| Outcome | Original (22) | Follow-up (14) |
|---------|---------------|----------------|
| Fixed (here or in #56) | 16 | 13 |
| Won't fix / N/A | 5 | — |
| Resolved by dropping M1 | — | 1 |
| Deferred | 1 | — |
