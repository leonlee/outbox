# Testing Patterns

**Analysis Date:** 2025-02-19

## Test Framework

**Runner:**

- JUnit Jupiter 5.10.2
- Config: Inherited via Maven Surefire plugin (version 3.5.4)
    - File: `pom.xml` in parent module
    - Setting: `<useModulePath>false</useModulePath>` to avoid Java 9+ issues

**Assertion Library:**

- JUnit Jupiter built-in assertions (`org.junit.jupiter.api.Assertions.*`)
- Common methods: `assertEquals()`, `assertTrue()`, `assertFalse()`, `assertThrows()`, `assertNull()`,
  `assertNotNull()`, `assertDoesNotThrow()`

**Run Commands:**

```bash
mvn test                                # Run all tests (~239 tests)
mvn -pl outbox-core test               # Run core module tests only
mvn -pl outbox-jdbc test               # Run JDBC module tests only
mvn test -Dtest=OutboxWriterTest       # Run single test class
mvn test -Dtest=OutboxWriterTest#*Test # Run matching test methods
```

**Coverage:**

- No code coverage tooling configured (Jacoco not present)
- Coverage requirements: Not enforced

## Test File Organization

**Location:**

- Source: `src/test/java/` parallel to `src/main/java/`
- Same package structure as production code
- Test utilities/fixtures in same package (not separate `fixtures/` folder)

**Naming Convention:**

- Test classes: `*Test.java` suffix for unit tests
- Integration tests: `*IntegrationTest.java` suffix
- Test helper classes (stubs, fakes): `Stub*.java` or `Recording*.java` prefix

**Structure:**

```
outbox-core/src/test/java/outbox/
├── OutboxWriterTest.java
├── OutboxTest.java
├── dispatch/
│   ├── OutboxDispatcherTest.java
│   ├── DispatcherWriterHookTest.java
│   ├── DispatcherPollerHandlerTest.java
│   ├── ExponentialBackoffRetryPolicyTest.java
│   ├── DefaultInFlightTrackerTest.java
│   ├── EventInterceptorTest.java
│   └── StubOutboxStore.java              # Test helper
├── registry/
│   └── DefaultListenerRegistryTest.java
├── dead/
│   └── DeadEventManagerTest.java
└── util/
    ├── DefaultJsonCodecTest.java
    └── DaemonThreadFactoryTest.java

outbox-jdbc/src/test/java/outbox/jdbc/
├── OutboxAcceptanceTest.java            # Full integration
├── OutboxDispatcherTest.java            # Dispatch with H2
├── OutboxPollerTest.java
├── JdbcOutboxStoreTest.java
├── OutboxCompositeTest.java
├── OutboxEdgeCaseTest.java
└── DataSourceConnectionProviderTest.java
```

## Test Structure

**Suite Organization:**

- Flat test class per public class (1:1 mapping)
- No test suite classes; JUnit discovers all `*Test.java` files automatically
- No `@Nested` classes; keep test methods flat in single test class

**Patterns:**

**1. Setup/Teardown Pattern (Database Tests):**

```java
class OutboxAcceptanceTest {
  private DataSource dataSource;
  private H2OutboxStore outboxStore;
  private DataSourceConnectionProvider connectionProvider;
  private ThreadLocalTxContext txContext;
  private JdbcTransactionManager txManager;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    this.dataSource = ds;
    this.outboxStore = new H2OutboxStore();
    this.connectionProvider = new DataSourceConnectionProvider(ds);
    this.txContext = new ThreadLocalTxContext();
    this.txManager = new JdbcTransactionManager(connectionProvider, txContext);

    try (Connection conn = ds.getConnection()) {
      createSchema(conn);
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute("DROP TABLE outbox_event");
    }
  }

  private void createSchema(Connection conn) throws SQLException {
    // Create test schema
  }
}
```

**2. Simple Unit Test Pattern:**

```java
@Test
void acquireSucceedsForNewEventId() {
  DefaultInFlightTracker tracker = new DefaultInFlightTracker();

  assertTrue(tracker.tryAcquire("event-1"));
}

@Test
void acquireFailsForAlreadyAcquiredEventId() {
  DefaultInFlightTracker tracker = new DefaultInFlightTracker();

  assertTrue(tracker.tryAcquire("event-1"));
  assertFalse(tracker.tryAcquire("event-1"));
}
```

**3. Builder Validation Pattern:**

```java
@Test
void builderRejectsNullConnectionProvider() {
  assertThrows(NullPointerException.class, () ->
      OutboxDispatcher.builder()
          .outboxStore(new StubOutboxStore())
          .listenerRegistry(new DefaultListenerRegistry())
          .build());
}

@Test
void builderRejectsMaxAttemptsLessThanOne() {
  assertThrows(IllegalArgumentException.class, () ->
      OutboxDispatcher.builder()
          .connectionProvider(stubCp())
          .outboxStore(new StubOutboxStore())
          .listenerRegistry(new DefaultListenerRegistry())
          .maxAttempts(0)
          .build());
}
```

**4. Async/Latch Pattern (for concurrent operations):**

```java
@Test
void commitFastPathPublishesAndMarksDone() throws Exception {
  CountDownLatch latch = new CountDownLatch(1);
  DefaultListenerRegistry publishers = new DefaultListenerRegistry()
      .register("UserCreated", event -> latch.countDown());

  OutboxDispatcher dispatcher = dispatcher(1, 100, 100, publishers);
  OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));

  String eventId;
  try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
    eventId = writer.write(EventEnvelope.ofJson("UserCreated", "{\"id\":1}"));
    tx.commit();
  }

  assertTrue(latch.await(2, TimeUnit.SECONDS));
  awaitStatus(eventId, EventStatus.DONE, 2_000);

  dispatcher.close();
}
```

**5. Atomic Transaction Test Pattern:**

```java
@Test
void atomicityRollbackDoesNotPersist() throws Exception {
  OutboxDispatcher dispatcher = dispatcher(0, 10, 10);
  OutboxWriter writer = new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));

  try (JdbcTransactionManager.Transaction tx = txManager.begin()) {
    writer.write(EventEnvelope.ofJson("TestEvent", "{}"));
    tx.rollback();  // Force rollback
  }

  assertEquals(0, countRows());
  dispatcher.close();
}
```

## Mocking

**Framework:**

- No mocking library (Mockito, EasyMock) configured
- Use **hand-written stub/fake implementations** instead
- Located in test package alongside real tests

**Patterns:**

**1. Stub Implementations (minimal, no assertions):**

```java
class StubOutboxStore implements OutboxStore {
  final AtomicInteger markDoneCount = new AtomicInteger();
  final AtomicInteger markRetryCount = new AtomicInteger();
  final AtomicInteger markDeadCount = new AtomicInteger();

  @Override
  public void insertNew(Connection conn, EventEnvelope event) {}

  @Override
  public int markDone(Connection conn, String eventId) {
    markDoneCount.incrementAndGet();
    return 1;
  }

  @Override
  public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
    markRetryCount.incrementAndGet();
    return 1;
  }

  // ... other methods ...
}
```

**2. Recording Stubs (capture calls for assertion):**

```java
// Used in OutboxWriterTest.java
class RecordingHook implements WriterHook {
  final AtomicInteger beforeWriteEvents = new AtomicInteger(0);
  final AtomicInteger afterWriteCalls = new AtomicInteger(0);
  final AtomicReference<List<EventEnvelope>> afterWriteEvents = new AtomicReference<>(List.of());

  @Override
  public List<EventEnvelope> beforeWrite(List<EventEnvelope> events) {
    beforeWriteEvents.incrementAndGet();
    return List.copyOf(events);
  }

  @Override
  public void afterWrite(List<EventEnvelope> events) {
    afterWriteCalls.incrementAndGet();
    afterWriteEvents.set(List.copyOf(events));
  }
}
```

**3. Test Transaction Context (in-memory, not real JDBC):**

```java
class StubTxContext implements TxContext {
  private final boolean active;
  private final List<Runnable> afterCommit = new ArrayList<>();
  private final List<Runnable> afterRollback = new ArrayList<>();

  public StubTxContext(boolean active) {
    this.active = active;
  }

  @Override
  public boolean isTransactionActive() {
    return active;
  }

  @Override
  public Connection currentConnection() {
    return null;  // Unused in tests
  }

  @Override
  public void afterCommit(Runnable callback) {
    if (active) afterCommit.add(callback);
  }

  void runAfterCommit() {
    afterCommit.forEach(Runnable::run);
  }

  void runAfterRollback() {
    afterRollback.forEach(Runnable::run);
  }
}
```

**What to Mock:**

- Database interactions (use `StubOutboxStore`, real `H2OutboxStore` for integration tests)
- Transactional context (use `StubTxContext` for unit tests, `ThreadLocalTxContext` for integration tests)
- Event listeners (use `DefaultListenerRegistry` with lambda callbacks)

**What NOT to Mock:**

- `EventEnvelope` (use real builder)
- `OutboxDispatcher` (use real, but with 0 workers for sync testing)
- `OutboxWriter` (use real with recording hooks)
- `DefaultListenerRegistry` (use real)

## Fixtures and Factories

**Test Data:**

- H2 in-memory database for all JDBC tests
- Schema created in `@BeforeEach`, dropped in `@AfterEach`
- Use `EventEnvelope.ofJson(type, payload)` factory for test events
- Use `UUID.randomUUID()` in connection URLs to isolate tests

**Example Fixture Creation:**

```java
private void createSchema(Connection conn) throws SQLException {
  conn.createStatement().execute(
      "CREATE TABLE outbox_event (" +
      "  event_id VARCHAR(26) NOT NULL PRIMARY KEY," +
      "  event_type VARCHAR(100) NOT NULL," +
      "  aggregate_type VARCHAR(100) NOT NULL," +
      "  aggregate_id VARCHAR(100)," +
      "  tenant_id VARCHAR(100)," +
      "  payload CLOB NOT NULL," +
      "  headers CLOB," +
      "  status INT NOT NULL," +
      "  attempts INT NOT NULL," +
      "  error TEXT," +
      "  created_at TIMESTAMP NOT NULL," +
      "  next_retry_at TIMESTAMP" +
      ")"
  );
}
```

**Helper Methods (in test class):**

```java
private OutboxDispatcher dispatcher(int workerCount, int hotQ, int coldQ) {
  return OutboxDispatcher.builder()
      .connectionProvider(connectionProvider)
      .outboxStore(outboxStore)
      .listenerRegistry(new DefaultListenerRegistry())
      .workerCount(workerCount)
      .hotQueueCapacity(hotQ)
      .coldQueueCapacity(coldQ)
      .build();
}

private OutboxDispatcher dispatcher(int workerCount, int hotQ, int coldQ, ListenerRegistry registry) {
  return OutboxDispatcher.builder()
      .connectionProvider(connectionProvider)
      .outboxStore(outboxStore)
      .listenerRegistry(registry)
      .workerCount(workerCount)
      .hotQueueCapacity(hotQ)
      .coldQueueCapacity(coldQ)
      .build();
}

private int countRows() throws SQLException {
  try (Connection conn = dataSource.getConnection()) {
    try (var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM outbox_event")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}

private void awaitStatus(String eventId, EventStatus status, long timeoutMs) throws SQLException, InterruptedException {
  long deadline = System.currentTimeMillis() + timeoutMs;
  while (System.currentTimeMillis() < deadline) {
    if (getStatus(eventId) == status.code()) {
      return;
    }
    Thread.sleep(10);
  }
  fail("Event did not reach " + status + " within " + timeoutMs + "ms");
}
```

## Test Types

**Unit Tests:**

- Scope: Single class in isolation with stubs/fakes
- Location: `outbox-core/src/test/java/` (most tests)
- Examples:
    - `OutboxWriterTest.java` - Tests writer behavior with `StubTxContext` and recording hooks
    - `DefaultInFlightTrackerTest.java` - Tests tracker de-duplication
    - `ExponentialBackoffRetryPolicyTest.java` - Tests backoff calculation with range assertions
    - `EventInterceptorTest.java` - Tests interceptor chaining

**Integration Tests:**

- Scope: Multiple components with real H2 database
- Location: `outbox-jdbc/src/test/java/` (JDBC-based integration tests)
- Examples:
    - `OutboxAcceptanceTest.java` - Full transaction lifecycle: write → hot dispatch → poller fallback
    - `OutboxDispatcherTest.java` - Dispatcher with H2 store and real transactions
    - `OutboxPollerTest.java` - Poller cycle with real DB rows
    - `OutboxCompositeTest.java` - Full `Outbox` builder scenarios
    - `SpringAdapterIntegrationTest.java` - Spring integration with real Spring transactions

**E2E Tests:**

- Not automated in test suite
- Manual verification: `samples/outbox-demo` and `samples/outbox-spring-demo` run end-to-end flows

## Common Patterns

**1. Async Testing with CountDownLatch:**

```java
@Test
void hotQueueIsPrioritizedOverColdQueue() throws Exception {
  List<String> order = new CopyOnWriteArrayList<>();
  CountDownLatch processed = new CountDownLatch(2);

  DefaultListenerRegistry listeners = new DefaultListenerRegistry()
      .register("Test", event -> {
        order.add(event.eventId());
        processed.countDown();
      });

  OutboxDispatcher dispatcher = /* ... */;

  EventEnvelope cold = EventEnvelope.builder("Test").eventId("cold").payloadJson("{}").build();
  EventEnvelope hot = EventEnvelope.builder("Test").eventId("hot").payloadJson("{}").build();

  dispatcher.enqueueCold(new QueuedEvent(cold, QueuedEvent.Source.COLD, 0));
  dispatcher.enqueueHot(new QueuedEvent(hot, QueuedEvent.Source.HOT, 0));

  assertTrue(processed.await(2, TimeUnit.SECONDS));
  assertEquals(List.of("hot", "cold"), order);  // Hot processed first
  dispatcher.close();
}
```

**2. Error Testing (exception assertion):**

```java
@Test
void writeThrowsWhenNoActiveTransaction() {
  StubTxContext txContext = new StubTxContext(false);
  RecordingOutboxStore store = new RecordingOutboxStore();

  OutboxWriter writer = new OutboxWriter(txContext, store);

  assertThrows(IllegalStateException.class, () ->
      writer.write(EventEnvelope.ofJson("Test", "{}")));
}
```

**3. Builder Validation (constructor contract):**

```java
@Test
void builderRejectsMaxAttemptsLessThanOne() {
  assertThrows(IllegalArgumentException.class, () ->
      OutboxDispatcher.builder()
          .connectionProvider(stubCp())
          .outboxStore(new StubOutboxStore())
          .listenerRegistry(new DefaultListenerRegistry())
          .maxAttempts(0)
          .build());
}
```

**4. State Isolation with Volatile Fields:**

```java
@Test
void ttlAllowsReacquisitionAfterExpiry() throws InterruptedException {
  DefaultInFlightTracker tracker = new DefaultInFlightTracker(50); // 50ms TTL

  assertTrue(tracker.tryAcquire("event-1"));
  assertFalse(tracker.tryAcquire("event-1"));

  Thread.sleep(100); // Wait for TTL to expire

  assertTrue(tracker.tryAcquire("event-1"));  // Should acquire again
}
```

**5. Jitter/Range Testing (for randomized behavior):**

- `ExponentialBackoffRetryPolicyTest.java` tests delay ranges rather than exact values
- Pattern: Assert that computed delay falls within expected bounds
- Note: Jitter uses `[0.5, 1.5)` multiplier — test with ranges, not point assertions

**6. Row/Status Polling (for eventual consistency):**

```java
private void awaitStatus(String eventId, EventStatus expected, long timeoutMs) throws SQLException {
  long deadline = System.currentTimeMillis() + timeoutMs;
  while (System.currentTimeMillis() < deadline) {
    if (getStatus(eventId) == expected.code()) {
      return;
    }
    Thread.sleep(10);
  }
  fail("Status did not change within " + timeoutMs + "ms");
}
```

## Test Organization Guidelines

**Naming Test Methods:**

- Descriptive sentence style: `testXxxWhenConditionThenBehavior()` or `xxxWhenCondition()`
- Examples:
    - `writeThrowsWhenNoActiveTransaction()`
    - `acquireSucceedsForNewEventId()`
    - `hotQueueIsPrioritizedOverColdQueue()`

**Test Size (lines per test):**

- Arrange (setup): 5-10 lines
- Act (execute): 1-3 lines
- Assert (verify): 1-5 lines
- Total per test: 10-20 lines typically

**Test Dependencies:**

- Tests must be order-independent
- `@BeforeEach` creates fresh fixtures (no shared state between tests)
- `@AfterEach` cleans up (drop tables, close connections)

## Known Test Behaviors

**Expected Log Output:**

- Some edge case tests in `OutboxEdgeCaseTest` produce SEVERE/WARNING logs
- This is expected; logs confirm error paths are exercised
- Not treated as test failures

**Flaky Test Notes:**

- Jitter-based tests (`ExponentialBackoffRetryPolicyTest`) use range assertions
- TTL/expiry tests use `Thread.sleep()` with buffer (100ms sleep after 50ms TTL)
- Async tests use `CountDownLatch.await()` with 2+ second timeout

**Database Isolation:**

- Each test gets unique H2 database URL: `jdbc:h2:mem:outbox_[UUID];MODE=MySQL;DB_CLOSE_DELAY=-1`
- Schema dropped in `@AfterEach`
- `MODE=MySQL` enables MySQL dialect compatibility (e.g., `DELIMIT BY IDENTIFIER`)

## Coverage Gaps

**Areas Not Extensively Tested:**

- Micrometer metrics export (`outbox-micrometer` has basic tests only)
- PostgreSQL-specific claim locking edge cases (manual verification against real PostgreSQL)
- High-concurrency dispatcher scenarios (tested but not stress-tested)
- JMH benchmarks in `benchmarks/` module (not published, informational only)

---

*Testing analysis: 2025-02-19*
