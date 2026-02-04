Outbox Framework Spec (Core No-Spring, JDBC-Based, Fast-path enqueue + DB-poller fallback)

0. Goals

Build a framework that:
	1.	Persists a unified event record into an outbox table within the current business DB transaction.
	2.	After successful business transaction commit, enqueues the event (payload in memory) into an in-process Dispatcher (fast path).
	3.	Dispatcher executes registered Publishers (send to MQ) and/or in-process async Handlers.
	4.	On success, Dispatcher updates outbox status to DONE; on failure updates to RETRY/DEAD.
	5.	A low-frequency Poller scans DB as fallback only (node crash, enqueue downgrade, missed enqueue) and enqueues unfinished events.
	6.	Delivery semantics: at-least-once; duplicates are allowed and must be handled downstream by eventId.

Constraints:
	•	Core MUST NOT depend on Spring (no Spring TX, no JdbcTemplate).
	•	DB access MUST use standard JDBC.
	•	Spring integration is optional and implemented via an adapter module.

Non-goals:
	•	Exactly-once end-to-end.
	•	Distributed transactions.

⸻

1. Architecture Overview

1.1 Components
	•	OutboxClient: API used by business code inside a transaction context.
	•	TxContext / TxHooks: abstraction for transaction lifecycle hooks (afterCommit/afterRollback).
	•	OutboxRepository (JDBC): insert/update/query via java.sql.Connection.
	•	Dispatcher: hot/cold queues + worker pool; executes publishers/handlers; updates outbox status.
	•	PublisherRegistry: MQ publishers.
	•	HandlerRegistry: in-process handlers.
	•	Poller: low-frequency fallback DB scan → enqueue cold events.
	•	InFlightTracker: in-memory dedupe.
	•	BackpressurePolicy: bounded queues + downgrade strategy.

1.2 Queues
	•	Dispatcher MUST prioritize:
	•	Hot Queue: afterCommit enqueue from business thread.
	•	Cold Queue: poller enqueue fallback.

⸻

2. Core Transaction Abstractions (No Spring)

2.1 TxContext (required by core)

Core needs a way to:
	•	access the current transaction’s JDBC Connection
	•	register callbacks that run after commit (fast path enqueue)
	•	detect “no active transaction” and fail fast

Define:

public interface TxContext {
  boolean isTransactionActive();
  Connection currentConnection(); // MUST be tx-bound
  void afterCommit(Runnable callback);
  void afterRollback(Runnable callback); // optional, for cleanup
}

Rules:
	•	currentConnection() MUST return the same connection used by business operations in this transaction.
	•	afterCommit() callback MUST run only if the transaction commits successfully.
	•	Core MUST fail-fast if publish() called when isTransactionActive()==false.

2.2 Default implementations

Core module does NOT provide a transaction manager. It only defines interfaces.
Implementations are provided by:
	•	JDBC-self-managed adapter (optional) for users who run transactions manually.
	•	Spring adapter (optional) for users with @Transactional.

⸻

3. Data Model (Outbox Table)

Table: outbox_event

Columns (MySQL 8 assumed; JDBC compatible):
	•	event_id VARCHAR(26 or 36) NOT NULL PRIMARY KEY
	•	event_type VARCHAR(128) NOT NULL
	•	aggregate_type VARCHAR(64) NULL
	•	aggregate_id VARCHAR(128) NULL
	•	tenant_id VARCHAR(64) NULL
	•	payload JSON NOT NULL (or LONGTEXT)
	•	headers JSON NULL
	•	status TINYINT NOT NULL  (0=NEW,1=DONE,2=RETRY,3=DEAD)
	•	attempts INT NOT NULL DEFAULT 0
	•	available_at DATETIME(6) NOT NULL
	•	created_at DATETIME(6) NOT NULL
	•	done_at DATETIME(6) NULL
	•	last_error TEXT NULL

Indexes:
	•	idx_status_available(status, available_at, created_at)
	•	optional idx_created_at(created_at)

⸻

4. Event Envelope

Define EventEnvelope:

Fields:
	•	String eventId (generated if null)
	•	String eventType (required)
	•	Instant occurredAt (default now)
	•	String aggregateType (optional)
	•	String aggregateId (optional)
	•	String tenantId (optional)
	•	Map<String,String> headers (optional)
	•	byte[] payloadBytes OR String payloadJson (required; must be immutable for dispatch)

Rule:
	•	Payload MUST be serialized once and reused for:
	•	DB insert
	•	fast-path dispatch publish
	•	cold-path dispatch publish (poller reads same payload)

⸻

5. Public API (Core)

5.1 OutboxClient

public interface OutboxClient {
  String publish(EventEnvelope event);
}

Semantics:
	•	MUST require an active transaction via TxContext.
	•	MUST insert outbox row (NEW) using TxContext.currentConnection() in the current transaction.
	•	MUST register TxContext.afterCommit(() -> dispatcher.enqueueHot(queuedEvent)).
	•	If enqueueHot fails due to backpressure, MUST NOT throw; rely on Poller fallback and emit metrics/log.

⸻

6. JDBC Repository Contract

6.1 Repository Interface

public interface OutboxRepository {
  void insertNew(Connection conn, EventEnvelope e); // within tx
  int markDone(Connection conn, String eventId);    // outside tx OK, idempotent
  int markRetry(Connection conn, String eventId, Instant nextAt, String error);
  int markDead(Connection conn, String eventId, String error);

  List<OutboxRow> pollPending(Connection conn, Instant now, Duration skipRecent, int limit);
}

6.2 JDBC rules
	•	MUST use PreparedStatement and bind parameters.
	•	MUST NOT close tx-bound connection (caller manages connection lifecycle).
	•	For non-tx updates (DONE/RETRY/DEAD), repository may open its own short-lived connection via DataSource (in dispatcher/poller modules), but core keeps it abstract.

6.3 SQL semantics (idempotent updates)

Mark done:

UPDATE outbox_event
SET status=1, done_at=?
WHERE event_id=? AND status<>1;

Retry:
	•	attempts increment and available_at update:

UPDATE outbox_event
SET status=2,
    attempts=attempts+1,
    available_at=?,
    last_error=?
WHERE event_id=? AND status<>1;

Dead:

UPDATE outbox_event
SET status=3,
    last_error=?
WHERE event_id=? AND status<>1;

Poll query:

SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, payload, headers, attempts, created_at
FROM outbox_event
WHERE status IN (0,2)
  AND available_at <= ?
  AND created_at <= ?
ORDER BY created_at
LIMIT ?;


⸻

7. Dispatcher Behavior (Core, No Spring)

7.1 Queue element

QueuedEvent:
	•	EventEnvelope envelope (payload already present for hot; for cold loaded from DB)
	•	Source source (HOT/COLD)

7.2 Processing flow

For each queued event:
	1.	InFlight dedupe: if eventId already inflight, drop.
	2.	Execute matching Publishers (MQ) and Handlers (in-process).
	3.	On success: update DB to DONE (idempotent); remove inflight.
	4.	On failure: update RETRY with backoff or DEAD after maxAttempts; remove inflight.

7.3 DB access for status updates

Dispatcher MUST be constructed with a DataSource (or ConnectionProvider) for short-lived update connections:

public interface ConnectionProvider {
  Connection getConnection() throws SQLException;
}

Dispatcher uses:
	•	open conn
	•	autoCommit true
	•	call repository markDone/markRetry/markDead
	•	close conn

7.4 No DB reads on hot path
	•	Hot path MUST NOT re-query payload.
	•	Cold path payload comes from poller.

7.5 Concurrency
	•	worker pool size configurable.
	•	hot queue higher priority than cold queue.

⸻

8. Poller (Fallback Only)

8.1 Polling schedule
	•	intervalMs default 5000
	•	skipRecentMs default 1000 (poller does not fetch very recent events)

8.2 Poller behavior
	•	uses ConnectionProvider to open a short-lived connection.
	•	calls repository.pollPending(now, skipRecent, batchSize)
	•	converts rows to EventEnvelope (payload from DB)
	•	enqueueCold into dispatcher (subject to cold queue backpressure)

⸻

9. Backpressure & Downgrade

9.1 Bounded queues
	•	hot and cold queues MUST be bounded.
	•	unbounded is forbidden.

9.2 Hot queue full behavior (default)
	•	publish() MUST NOT throw.
	•	MUST log + increment metric and rely on poller fallback.

9.3 Cold queue full behavior
	•	poller should stop enqueueing for this cycle and retry next cycle.

⸻

10. Retry Policy

Configurable:
	•	baseDelayMs (default 200)
	•	maxDelayMs (default 60000)
	•	maxAttempts (default 10)
	•	exponential with jitter

Backoff formula (reference):
	•	delay = min(maxDelay, baseDelay * 2^(attempts-1)) * random(0.5..1.5)

⸻

11. Idempotency Requirements
	•	Publishers MUST include eventId in MQ message header/body.
	•	Downstream must dedupe by eventId.
	•	Framework assumes at-least-once.

⸻

12. Observability

Metrics:
	•	enqueue counts/drops
	•	queue depths
	•	dispatch success/failure/dead
	•	oldest pending lag (computed by poller)
Logs:
	•	WARN on hot enqueue drop (downgrade)
	•	ERROR on DEAD transition

⸻

13. Configuration

Core should define a POJO config object (no Spring @ConfigurationProperties in core):

public final class OutboxConfig {
  int dispatcherWorkers;
  int hotQueueCapacity;
  int coldQueueCapacity;

  boolean pollerEnabled;
  long pollerIntervalMs;
  int pollerBatchSize;
  long pollerSkipRecentMs;

  long retryBaseDelayMs;
  long retryMaxDelayMs;
  int retryMaxAttempts;
}

Spring adapter may bind external properties to this config.

⸻

14. Acceptance Tests (Core-level)
	1.	Atomicity

	•	Begin tx manually, publish event, rollback
	•	Expect: outbox row not present

	2.	Commit + fast path

	•	Begin tx, publish event, commit
	•	Expect: dispatcher receives HOT event; publisher invoked; outbox status DONE

	3.	Queue overflow downgrade

	•	hot queue capacity small, force drop
	•	Expect: publish returns ok, outbox row NEW
	•	Start poller, expect row processed to DONE

	4.	Retry/Dead

	•	publisher fails repeatedly
	•	expect attempts increments, RETRY then DEAD after maxAttempts

⸻

15. Optional Spring Adapter (Not part of core)

Provide module outbox-spring-adapter that implements TxContext using Spring:
	•	isTransactionActive() via TransactionSynchronizationManager.isActualTransactionActive()
	•	currentConnection() via DataSourceUtils.getConnection(dataSource)
	•	afterCommit() via TransactionSynchronizationManager.registerSynchronization(...)

This adapter depends on Spring; core must not.

⸻

16. Implementation Notes (Codex Guidance)
	•	Structure suggested:
	•	outbox-core (EventEnvelope, OutboxClient, Dispatcher, interfaces, config)
	•	outbox-jdbc (JDBC repository implementation, default ConnectionProvider)
	•	outbox-spring-adapter (TxContext Spring implementation)
	•	Ensure payload serialization happens once and is reused.
	•	Do not close tx-bound connection in core publish().
	•	Use BlockingQueue bounded; priority hot > cold.
	•	Use ConcurrentHashMap/TTL cache for inflight dedupe.

