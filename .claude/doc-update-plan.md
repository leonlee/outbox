# Document Update Plan for DispatchResult/RetryAfterException

## Status: COMPLETE — All documents updated.

## Committed
Commit `2180f9d` on `main` — all code and tests complete, passing.

## CLAUDE.md Updates Needed

### 1. Package Structure (line ~55)
Add after `EventListener.java`:
```
├── DispatchResult.java (sealed interface: Done, RetryAfter)
├── RetryAfterException.java
```

### 2. Key Abstractions (after line ~198, before Event Flow)
Add new entries:
- **DispatchResult**: Sealed interface returned by `EventListener.handleEvent()`. `Done` (singleton) marks event complete. `RetryAfter(Duration)` defers re-delivery without counting against `maxAttempts`.
- **RetryAfterException**: RuntimeException with handler-specified retry delay. Unlike `DispatchResult.RetryAfter`, counts against `maxAttempts`. Dispatcher uses exception's `retryAfter()` instead of `RetryPolicy`.

### 3. OutboxStore entry (line ~141)
Add `markDeferred` to the method list: `insertNew`, `insertBatch`, `markDone`, `markRetry`, `markDead`, `markDeferred`, `pollPending`...

### 4. OutboxDispatcher entry (line ~157-161)
Update: "call `listener.onEvent()`" → "call `listener.handleEvent()`"
Add: "→ handle `DispatchResult` (Done/RetryAfter) → markDone/markDeferred/markRetry/markDead"

### 5. Event Flow (lines 200-209)
Step 4: Update from `call listener.onEvent() → update status to DONE/RETRY/DEAD` to:
```
4. OutboxDispatcher workers process events one at a time: acquire in-flight → run interceptors → find listener via
   `(aggregateType, eventType)` → call `listener.handleEvent()` → handle DispatchResult:
   - Done (or null): markDone
   - RetryAfter: markDeferred (no attempt increment)
   - Exception: markRetry (or markDead if maxAttempts exhausted)
   - RetryAfterException: markRetry with handler delay (counts against maxAttempts)
   - UnroutableEventException: markDead immediately (no retry)
```

### 6. MetricsExporter / MicrometerMetricsExporter
Add `incrementDispatchDeferred` to the description.

## SPEC.md Updates Needed
- Dispatch behavioral rules: add DispatchResult handling
- OutboxStore SPI: add markDeferred contract
- EventListener: document handleEvent default method
- RetryAfterException: document semantics

## TUTORIAL.md Updates Needed
- Add example showing handleEvent returning RetryAfter
- Add example showing RetryAfterException for rate limiting

## README.md Updates Needed
- If architecture diagram or feature list exists, add handler-controlled retry

## MEMORY.md Updates Needed
- Add DispatchResult and RetryAfterException to Key Patterns
