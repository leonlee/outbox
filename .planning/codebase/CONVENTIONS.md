# Coding Conventions

**Analysis Date:** 2025-02-19

## Naming Patterns

**Files:**

- Classes: `UpperCamelCase` (e.g., `OutboxWriter.java`, `EventEnvelope.java`)
- Packages: lowercase dot-separated, feature-scoped (e.g., `outbox.dispatch`, `outbox.registry`, `outbox.jdbc.store`)
- Test files: Class name + `Test` suffix (e.g., `OutboxWriterTest.java`)
- Integration tests: Class name + `IntegrationTest` suffix (e.g., `SpringAdapterIntegrationTest.java`)

**Functions/Methods:**

- `lowerCamelCase` (e.g., `writeAll()`, `markDone()`, `currentConnection()`)
- Builder methods: fluent style returning `this` (e.g., `.eventId()`, `.payloadJson()`, `.workerCount()`)
- Private helper methods: descriptive names (e.g., `runSafely()`, `withConnection()`)

**Variables:**

- Local variables: `lowerCamelCase` (e.g., `eventId`, `txContext`, `processed`)
- Instance fields: `lowerCamelCase` (e.g., `hotQueue`, `outboxStore`, `writerHook`)
- Final fields: `lowerCamelCase` (e.g., `tableName`, `jsonCodec`)

**Types/Constants:**

- Interfaces: `UpperCamelCase`, often with verb/noun combo (e.g., `ListenerRegistry`, `OutboxStore`, `EventListener`)
- Constants: `UPPER_SNAKE_CASE` (e.g., `MAX_PAYLOAD_BYTES`, `DEFAULT_TABLE`, `PENDING_STATUS_IN`)
- Sealed/abstract base classes: `UpperCamelCase` with descriptive prefix (e.g., `AbstractJdbcOutboxStore`,
  `AbstractBuilder`)

## Code Style

**Formatting:**

- Indentation: 2 spaces (no tabs)
- Braces: Same line (opening), K&R style (e.g., `if (...) {` on same line)
- Line length: No explicit limit; wraps naturally
- No formatter configured; follow existing style patterns

**Example formatting from codebase:**

```java
private EventEnvelope(Builder builder) {
  this.eventId = builder.eventId == null ? newEventId() : builder.eventId;
  this.eventType = Objects.requireNonNull(builder.eventType, "eventType");
  if (this.eventType.isEmpty()) {
    throw new IllegalArgumentException("eventType cannot be empty");
  }
}
```

**Linting:**

- No static linter configured (ESLint/Checkstyle not present)
- Rely on IDE inspection warnings and explicit code review
- Error messages must be descriptive (include values where helpful)

## Import Organization

**Order:**

1. `package` declaration
2. Blank line
3. `import` statements (organized by domain)
    - Within-project imports (`outbox.*`) first
    - External imports (`com.github.f4b6a3.*`, `javax.sql.*`) next
    - Java standard library (`java.*`) last
    - No wildcard imports
4. Blank line
5. Class declaration

**Path Aliases:**

- No aliasing used; fully qualified names
- Example from `OutboxWriter.java`:
  ```java
  package outbox;

  import outbox.spi.OutboxStore;
  import outbox.spi.TxContext;

  import java.sql.Connection;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Objects;
  import java.util.logging.Level;
  import java.util.logging.Logger;
  ```

## Error Handling

**Patterns:**

- **Validation errors:** `Objects.requireNonNull()` for null checks; `IllegalArgumentException` for invalid input
  ```java
  this.txContext = Objects.requireNonNull(txContext, "txContext");
  if (attempts <= 0) {
    throw new IllegalArgumentException("maxAttempts must be >= 1");
  }
  ```

- **State errors:** `IllegalStateException` when operation cannot proceed
  ```java
  if (!txContext.isTransactionActive()) {
    throw new IllegalStateException("No active transaction");
  }
  ```

- **Silent failures in async contexts:** Log at WARNING/SEVERE level; do not throw from hooks/callbacks
  ```java
  private void runSafely(String phase, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      logger.log(Level.WARNING, "WriterHook." + phase + " failed", ex);
    }
  }
  ```

- **Callback chaining with suppression:** Collect first exception; add subsequent as suppressed (ThreadLocalTxContext
  pattern)
  ```java
  RuntimeException first = null;
  for (Runnable callback : callbacks) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      if (first == null) first = e;
      else first.addSuppressed(e);
    }
  }
  if (first != null) throw first;
  ```

- **SQLException handling:** Log at SEVERE; propagate or mark event DEAD depending on context
  ```java
  try {
    conn.createStatement().execute(sql);
  } catch (SQLException e) {
    logger.log(Level.SEVERE, "Failed to mark DONE for eventId=" + eventId, e);
  }
  ```

## Logging

**Framework:** `java.util.logging` (JUL), not SLF4J or Log4j

**Pattern:**

- Declare static logger: `private static final Logger logger = Logger.getLogger(ClassName.class.getName());`
- Use `logger.log(Level.LEVEL, "message", exception)` for all logging
- **Levels used:**
    - `INFO`: Normal operational messages (purge cycles, scheduler startup)
    - `WARNING`: Recoverable issues (failed WriterHook phases, retries)
    - `SEVERE`: Critical failures (poll/purge cycle failure, connection errors)

**Examples:**

```java
logger.log(Level.WARNING, "WriterHook." + phase + " failed", ex);
logger.log(Level.SEVERE, "Failed to obtain connection for purge", e);
logger.log(Level.INFO, "Purged {0} terminal events older than {1}", new Object[]{count, cutoff});
```

## Comments

**When to Comment:**

- Document **why**, not what (code says what; comments say why)
- Document tricky algorithms or non-obvious decisions
- Document SPI extension points for implementers

**JSDoc/JavaDoc:**

- Use for all public classes, interfaces, and methods
- Include `@param`, `@return`, `@throws` for non-obvious cases
- Include `@see` cross-references to related APIs
- Use `<p>` for multi-paragraph descriptions

**Example from codebase:**

```java
/**
 * Retry policy using exponential backoff with jitter.
 *
 * <p>Delay formula: {@code baseDelay * 2^(attempt-1)}, capped at {@code maxDelay},
 * with random jitter in the range [0.5, 1.5).
 */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {
  /**
   * @param baseDelayMs base delay for the first retry (milliseconds)
   * @param maxDelayMs  maximum delay cap (milliseconds)
   */
  public ExponentialBackoffRetryPolicy(long baseDelayMs, long maxDelayMs) { ... }
}
```

**Package-level documentation:**

- Use `package-info.java` in each package with module overview
- Example: `outbox-core/src/main/java/outbox/package-info.java` documents main API entry points

## Function Design

**Size:**

- Small, focused methods (typically < 30 lines)
- Long functions acceptable if they form a cohesive algorithm (e.g., builder pattern constructor validation)

**Parameters:**

- Prefer explicit parameters over builder arguments when count < 3
- Use builder for > 3 required arguments (e.g., `OutboxDispatcher.builder()`)
- Null-check all non-optional params with `Objects.requireNonNull()`

**Return Values:**

- Prefer `List.of()` immutable collections over mutable ones
- Use `List.copyOf()` when returning defensive copies
- Return `null` only when semantically meaningful (e.g., `write()` returns null if suppressed by hook)
- Prefer `Optional` or empty collections over null for collections

**Example:**

```java
public List<String> writeAll(List<EventEnvelope> events) {
  if (!txContext.isTransactionActive()) {
    throw new IllegalStateException("No active transaction");
  }
  Objects.requireNonNull(events, "events");
  if (events.isEmpty()) {
    return List.of();  // Empty immutable list
  }

  List<EventEnvelope> transformed = writerHook.beforeWrite(List.copyOf(events));
  if (transformed == null || transformed.isEmpty()) {
    return List.of();
  }

  // ... implementation

  return ids;  // Always return list, never null
}
```

## Module Design

**Exports:**

- Each module has a clear public API in root package (e.g., `outbox.OutboxWriter`, `outbox.spi.OutboxStore`)
- SPI interfaces in `spi` sub-package (e.g., `outbox.spi.TxContext`, `outbox.spi.OutboxStore`)
- Feature implementations in feature packages (e.g., `outbox.dispatch.*`, `outbox.poller.*`)
- Utilities in `util` sub-package (e.g., `outbox.util.JsonCodec`)

**Barrel Files:**

- No barrel/re-export files (index.java)
- All imports use fully qualified package paths

**Package Structure Example:**

```
outbox-core/src/main/java/outbox/
├── package-info.java          # Root API docs
├── Outbox.java                # Composite builder (main entry point)
├── OutboxWriter.java          # Main API
├── EventEnvelope.java         # Data model
├── EventType.java             # Interface
├── StringEventType.java       # Record
├── EventListener.java         # Listener interface
├── WriterHook.java            # Lifecycle hook
│
├── spi/                       # Extension Point Interfaces
│   ├── TxContext.java
│   ├── OutboxStore.java
│   ├── ConnectionProvider.java
│   └── package-info.java
│
├── dispatch/                  # Event dispatcher feature
│   ├── OutboxDispatcher.java
│   ├── DispatcherWriterHook.java
│   ├── RetryPolicy.java
│   └── package-info.java
│
├── poller/                    # Event poller feature
│   ├── OutboxPoller.java
│   └── package-info.java
│
└── util/                      # Utilities
    ├── JsonCodec.java
    ├── DefaultJsonCodec.java
    └── DaemonThreadFactory.java
```

**JDBC Implementation Package (`outbox.jdbc`):**

- Root: JdbcTemplate, DataSourceConnectionProvider, TableNames, OutboxStoreException
- `store/`: AbstractJdbcOutboxStore hierarchy (H2/MySQL/PostgreSQL subclasses)
- `tx/`: ThreadLocalTxContext, JdbcTransactionManager
- `purge/`: AbstractJdbcEventPurger and AbstractJdbcAgeBasedPurger hierarchies

## Data Classes

**Records vs. Classes:**

- Use Java `record` for immutable data carriers with no custom behavior:
    - `StringEventType` (String wrapper)
    - `StringAggregateType` (String wrapper)
    - `OutboxEvent` (database row mapping)
    - `QueuedEvent` (envelope + metadata tuple)

**Builder Pattern:**

- Use for complex objects with optional fields and validation
- Example: `EventEnvelope.Builder`, `OutboxDispatcher.Builder`, `OutboxPoller.Builder`
- Inner static final class; private constructor
- Fluent methods returning `this`
- Validation in outer class constructor (not builder)

**Immutability:**

- All fields `private final` unless explicitly mutable
- Defensive copy on return: `Arrays.copyOf()`, `List.copyOf()`, `Collections.unmodifiableMap()`
- No setters; construction is immutable

## Sealed/Abstract Classes

**Sealed Classes:**

- Use `sealed` modifier for finite type hierarchies (e.g., `Outbox.AbstractBuilder<B>`)
- Permits list specifies all concrete implementations

**Abstract Base Classes:**

- Prefix with `Abstract` (e.g., `AbstractJdbcOutboxStore`, `AbstractJdbcEventPurger`)
- Protected fields for subclass access
- Public abstract methods for extension points (e.g., `public abstract String name()`)
- Protected helper methods for shared implementation

---

*Conventions analysis: 2025-02-19*
