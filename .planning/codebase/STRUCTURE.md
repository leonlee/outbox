# Codebase Structure

**Analysis Date:** 2026-02-19

## Directory Layout

```
outbox/
├── outbox-core/                    # Core API, zero external deps (except ULID)
│   ├── src/main/java/io/outbox/
│   │   ├── Outbox.java            # Composite builder (singleNode/multiNode/ordered/writerOnly)
│   │   ├── OutboxWriter.java      # Primary write entry point
│   │   ├── EventEnvelope.java     # Immutable event with ULID, metadata, payload
│   │   ├── EventListener.java     # Listener interface
│   │   ├── WriterHook.java        # Batch write lifecycle
│   │   ├── EventType.java         # Event type interface
│   │   ├── AggregateType.java     # Aggregate type interface
│   │   ├── StringEventType.java   # Record impl for EventType
│   │   ├── StringAggregateType.java # Record impl for AggregateType
│   │   │
│   │   ├── spi/                    # Extension point interfaces
│   │   │   ├── TxContext.java      # Transaction lifecycle abstraction
│   │   │   ├── ConnectionProvider.java
│   │   │   ├── OutboxStore.java    # Persistence contract
│   │   │   ├── EventPurger.java    # TTL-based event cleanup
│   │   │   └── MetricsExporter.java # Observability SPI
│   │   │
│   │   ├── model/                  # Domain objects
│   │   │   ├── OutboxEvent.java    # Persistent event record (read)
│   │   │   └── EventStatus.java    # NEW, RETRY, DONE, DEAD enum
│   │   │
│   │   ├── dispatch/               # Hot-path event processor
│   │   │   ├── OutboxDispatcher.java # Dual-queue worker threads
│   │   │   ├── DispatcherWriterHook.java # WriterHook → hot queue
│   │   │   ├── DispatcherPollerHandler.java # OutboxPollerHandler → cold queue
│   │   │   ├── RetryPolicy.java    # Delay computation interface
│   │   │   ├── ExponentialBackoffRetryPolicy.java
│   │   │   ├── InFlightTracker.java # Deduplication interface
│   │   │   ├── DefaultInFlightTracker.java
│   │   │   ├── QueuedEvent.java    # Record: (envelope, source, attempts)
│   │   │   ├── EventInterceptor.java # before/after dispatch hooks
│   │   │   └── UnroutableEventException.java
│   │   │
│   │   ├── poller/                 # Cold-path fallback
│   │   │   ├── OutboxPoller.java   # Scheduled DB scanner
│   │   │   └── OutboxPollerHandler.java # Poller event handler interface
│   │   │
│   │   ├── dead/                   # Dead event operations
│   │   │   └── DeadEventManager.java # Query, count, replay DEAD events
│   │   │
│   │   ├── purge/                  # Event cleanup
│   │   │   └── OutboxPurgeScheduler.java # Scheduled terminal event purger
│   │   │
│   │   ├── registry/               # Event routing
│   │   │   ├── ListenerRegistry.java # (aggregateType, eventType) → listener
│   │   │   └── DefaultListenerRegistry.java # HashMap-based impl
│   │   │
│   │   └── util/                   # Shared utilities
│   │       ├── DaemonThreadFactory.java # Named daemon threads
│   │       ├── JsonCodec.java      # Event headers codec interface
│   │       └── DefaultJsonCodec.java # Zero-dependency JSON codec
│   │
│   └── src/test/java/io/outbox/      # Unit/integration tests (*Test.java)
│
├── outbox-jdbc/                    # JDBC persistence & transaction management
│   ├── src/main/java/io/outbox/jdbc/
│   │   ├── JdbcTemplate.java       # Lightweight query helper (update, query, updateReturning)
│   │   ├── TableNames.java         # Table name validation (regex: [a-zA-Z_][a-zA-Z0-9_]*)
│   │   ├── OutboxStoreException.java
│   │   ├── DataSourceConnectionProvider.java # ConnectionProvider → DataSource
│   │   │
│   │   ├── store/                  # OutboxStore JDBC hierarchy
│   │   │   ├── AbstractJdbcOutboxStore.java # Shared SQL, row mapper, default claimPending
│   │   │   ├── H2OutboxStore.java   # Subquery-based two-phase claim (not FOR UPDATE)
│   │   │   ├── MySqlOutboxStore.java # UPDATE...ORDER BY...LIMIT claim
│   │   │   ├── PostgresOutboxStore.java # FOR UPDATE SKIP LOCKED + RETURNING
│   │   │   ├── JdbcOutboxStores.java # ServiceLoader registry + detect(DataSource) auto-detection
│   │   │   └── META-INF/services/io.outbox.jdbc.store.AbstractJdbcOutboxStore (ServiceLoader)
│   │   │
│   │   ├── purge/                  # EventPurger JDBC hierarchies
│   │   │   ├── AbstractJdbcEventPurger.java # Status-based: terminal events only (DONE+DEAD)
│   │   │   ├── H2EventPurger.java
│   │   │   ├── MySqlEventPurger.java
│   │   │   ├── PostgresEventPurger.java
│   │   │   ├── AbstractJdbcAgeBasedPurger.java # Age-based: all events by age (CDC mode)
│   │   │   ├── H2AgeBasedPurger.java
│   │   │   ├── MySqlAgeBasedPurger.java
│   │   │   └── PostgresAgeBasedPurger.java
│   │   │
│   │   └── tx/                     # Transaction management
│   │       ├── ThreadLocalTxContext.java # TxContext → ThreadLocal JDBC
│   │       └── JdbcTransactionManager.java # Manual transaction API
│   │
│   └── src/test/java/io/outbox/jdbc/
│
├── outbox-spring-adapter/          # Optional Spring integration
│   ├── src/main/java/io/outbox/spring/
│   │   └── SpringTxContext.java    # TxContext → Spring PlatformTransactionManager
│   └── src/test/java/io/outbox/spring/
│
├── outbox-micrometer/              # Optional Micrometer metrics
│   ├── src/main/java/io/outbox/micrometer/
│   │   └── MicrometerMetricsExporter.java # MetricsExporter → Micrometer counters/gauges
│   └── src/test/java/io/outbox/micrometer/
│
├── benchmarks/                      # JMH performance benchmarks (not published)
│   ├── OutboxWriteBenchmark.java
│   ├── OutboxDispatchBenchmark.java
│   ├── OutboxPollerBenchmark.java
│   └── BenchmarkDataSourceFactory.java
│
├── samples/                         # Example applications
│   ├── outbox-demo/                # Simple JDBC demo with H2 (no Spring)
│   │   └── src/main/java/io/outbox/demo/OutboxDemo.java
│   │
│   ├── outbox-spring-demo/         # Spring Boot REST API demo
│   │   └── src/main/java/io/outbox/demo/spring/
│   │       ├── Application.java
│   │       ├── EventController.java # POST /events/user-created, /events/order-placed
│   │       └── OutboxConfiguration.java # @Bean OutboxWriter, EventListener registry
│   │
│   └── outbox-multi-ds-demo/       # Multi-datasource demo (Orders + Inventory H2s)
│       └── src/main/java/io/outbox/demo/multids/MultiDatasourceDemo.java
│
├── .planning/                       # GSD planning documents (generated)
│   └── codebase/                    # ARCHITECTURE.md, STRUCTURE.md, etc.
│
├── pom.xml                          # Parent pom (v0.7.0-SNAPSHOT, Java 17)
├── CLAUDE.md                        # Development guide (build, release, architecture)
├── README.md                        # Project overview
├── SPEC.md                          # Technical specification (17 sections)
├── TUTORIAL.md                      # Step-by-step examples
└── OBJECTIVE.md                     # Project goals & acceptance criteria
```

## Directory Purposes

**outbox-core:**

- Purpose: Core framework API, zero external dependencies (except ULID)
- Contains: Outbox builders, Writer, Dispatcher, Poller, Registry, event model, SPI interfaces
- Key files: `Outbox.java` (entry point), `OutboxWriter.java`, `OutboxDispatcher.java`, `OutboxPoller.java`

**outbox-jdbc:**

- Purpose: JDBC persistence backend, optional Spring adapter integration
- Contains: OutboxStore hierarchy (H2/MySQL/PostgreSQL), TxContext impl (ThreadLocalTxContext), purgers (status-based +
  age-based)
- Key files: `AbstractJdbcOutboxStore.java`, `JdbcOutboxStores.java` (auto-detection), `ThreadLocalTxContext.java`

**outbox-spring-adapter:**

- Purpose: Spring transaction integration (optional)
- Contains: SpringTxContext adapter (TxContext → PlatformTransactionManager)
- Key files: `SpringTxContext.java`

**outbox-micrometer:**

- Purpose: Micrometer metrics export (optional)
- Contains: MicrometerMetricsExporter (counters, gauges)
- Key files: `MicrometerMetricsExporter.java`

**benchmarks:**

- Purpose: JMH performance benchmarks (development only, not published)
- Contains: Write throughput, dispatch latency, poller throughput benchmarks
- Generated: No (source code)
- Committed: Yes (for dev/CI reference)

**samples:**

- Purpose: Runnable example applications
- Contains: outbox-demo (JDBC), outbox-spring-demo (Spring Boot), outbox-multi-ds-demo (multi-DataSource)
- Generated: No
- Committed: Yes (for documentation)

## Key File Locations

**Entry Points:**

- `outbox-core/src/main/java/io/outbox/Outbox.java`: Main composite builder (singleNode, multiNode, ordered, writerOnly)
- `outbox-core/src/main/java/io/outbox/OutboxWriter.java`: Event write API (write, writeAll)
- `samples/outbox-demo/src/main/java/io/outbox/demo/OutboxDemo.java`: Minimal runnable example
- `samples/outbox-spring-demo/src/main/java/io/outbox/demo/spring/Application.java`: Spring Boot demo

**Configuration:**

- `pom.xml`: Parent pom with dependency versions (Java 17, JUnit 5, H2, Spring 6.2, Micrometer 1.14, MySQL 8.3,
  PostgreSQL 42.7)
- `outbox-core/pom.xml`: Core module dependencies (ULID only)
- `outbox-jdbc/pom.xml`: JDBC module (HikariCP, H2/MySQL/PostgreSQL test deps)
- `outbox-spring-adapter/pom.xml`: Spring framework dependency
- `CLAUDE.md`: Build commands and architecture overview

**Core Logic:**

- `outbox-core/src/main/java/io/outbox/dispatch/OutboxDispatcher.java`: Dual-queue processor, fair draining
- `outbox-core/src/main/java/io/outbox/poller/OutboxPoller.java`: Scheduled poller (single/multi-node modes)
- `outbox-jdbc/src/main/java/io/outbox/jdbc/store/AbstractJdbcOutboxStore.java`: Base persistence, subclass overrides for
  DB-specific claim strategies
- `outbox-core/src/main/java/io/outbox/registry/DefaultListenerRegistry.java`: Event routing by (aggregateType, eventType)

**Testing:**

- `outbox-core/src/test/java/io/outbox/`: ~80+ tests (OutboxDispatcherTest, OutboxPollerTest, ListenerRegistryTest,
  EventEnvelopeTest, etc.)
- `outbox-jdbc/src/test/java/io/outbox/jdbc/`: ~40+ tests (store/tx/purge integrations with H2)
- Test pattern: H2 in-memory database, *Test suffix, JUnit Jupiter
- `outbox-spring-adapter/src/test/java/io/outbox/spring/SpringTxContextTest.java`
- `outbox-micrometer/src/test/java/io/outbox/micrometer/MicrometerMetricsExporterTest.java`

## Naming Conventions

**Files:**

- Classes: `UpperCamelCase.java` (e.g., OutboxDispatcher.java, DefaultListenerRegistry.java)
- Test classes: `*Test.java` (e.g., OutboxDispatcherTest.java, EventEnvelopeTest.java)
- Integration tests: `*IntegrationTest.java` (e.g., JdbcOutboxStoreIntegrationTest.java)

**Directories:**

- Packages: `io.outbox.featureName` (e.g., io.outbox.dispatch, io.outbox.jdbc.store, io.outbox.spring)
- Module structure: `outbox-<feature>` (e.g., outbox-core, outbox-jdbc, outbox-spring-adapter)

**Code:**

- Methods: `lowerCamelCase` (e.g., enqueueHot(), markDone(), isTransactionActive())
- Constants: `UPPER_SNAKE_CASE` (e.g., MAX_PAYLOAD_BYTES, DEFAULT_TABLE, PENDING_STATUS_IN)
- Fields: `lowerCamelCase` (e.g., eventId, txContext, connectionProvider)
- 2-space indentation, braces on same line (no formatter configured)

## Where to Add New Code

**New Feature (e.g., event filtering, circuit breaker):**

- Primary code: `outbox-core/src/main/java/io/outbox/<featureName>/` (new package)
- Tests: `outbox-core/src/test/java/io/outbox/<featureName>/<FeatureName>Test.java`
- Example: EventInterceptor chain → `outbox-core/src/main/java/io/outbox/dispatch/EventInterceptor.java` (interface)

**New Component/Module (e.g., Debezium integration):**

- Implementation: `outbox-<component>/src/main/java/io/outbox/<component>/`
- POM: `outbox-<component>/pom.xml`
- Tests: `outbox-<component>/src/test/java/io/outbox/<component>/`
- Register via pom.xml `<modules>` in parent and dependency in samples/other modules as needed

**Utilities:**

- Shared helpers: `outbox-core/src/main/java/io/outbox/util/` (e.g., DaemonThreadFactory.java, JsonCodec.java)
- JDBC utilities: `outbox-jdbc/src/main/java/io/outbox/jdbc/` (root, e.g., JdbcTemplate.java, TableNames.java)
- Spring-specific: `outbox-spring-adapter/src/main/java/io/outbox/spring/`

**Database-Specific Code:**

- OutboxStore subclass: `outbox-jdbc/src/main/java/io/outbox/jdbc/store/<Database>OutboxStore.java`
- EventPurger subclass: `outbox-jdbc/src/main/java/io/outbox/jdbc/purge/<Database>EventPurger.java` (status-based) or
  `<Database>AgeBasedPurger.java` (age-based)
- Register in `META-INF/services/io.outbox.jdbc.store.AbstractJdbcOutboxStore` (ServiceLoader)

**Samples/Documentation:**

- Demo applications: `samples/outbox-<demo>/src/main/java/io/outbox/demo/<demo>/`
- Docs: Root level `.md` files (README.md, SPEC.md, TUTORIAL.md, CLAUDE.md)

## Special Directories

**target/:**

- Purpose: Maven build output
- Generated: Yes (mvn clean package)
- Committed: No (.gitignored)

**.planning/codebase/:**

- Purpose: GSD planning documents (architecture, structure, conventions, testing, concerns)
- Generated: Yes (by /gsd:map-codebase agent)
- Committed: Yes (tracked in .git)

**META-INF/services/:**

- Purpose: ServiceLoader registry for pluggable implementations
- Location: `outbox-jdbc/target/classes/META-INF/services/io.outbox.jdbc.store.AbstractJdbcOutboxStore`
- Contains: Fully qualified class names, one per line (H2OutboxStore, MySqlOutboxStore, PostgresOutboxStore)
- Generated: Yes (built from source)
- Committed: No (generated at build time)

**.github/workflows/:**

- Purpose: CI/CD automation
- Key files: `ci.yml` (Java 17+21 matrix tests), `publish.yml` (deploy on v* tags), `docs.yml` (path-filtered),
  `schema-diff.yml`
- Committed: Yes

---

*Structure analysis: 2026-02-19*
