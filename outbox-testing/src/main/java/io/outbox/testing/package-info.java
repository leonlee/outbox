/**
 * Test fixtures for unit testing code that uses the outbox framework.
 *
 * <p>This module provides in-memory implementations that require no JDBC or database:
 * <ul>
 *   <li>{@link io.outbox.testing.InMemoryOutboxStore} — {@code ConcurrentHashMap}-backed store</li>
 *   <li>{@link io.outbox.testing.StubTxContext} — controllable transaction context</li>
 *   <li>{@link io.outbox.testing.RecordingWriterHook} — captures all lifecycle invocations</li>
 *   <li>{@link io.outbox.testing.NoOpConnectionProvider} — null connection provider</li>
 *   <li>{@link io.outbox.testing.OutboxTestSupport} — convenience builder that wires everything together</li>
 * </ul>
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * var test = OutboxTestSupport.create()
 *     .register("OrderPlaced", event -> { ... })
 *     .build();
 *
 * test.writer().write(EventEnvelope.ofJson("OrderPlaced", "{}"));
 * test.txContext().runAfterCommit();
 * assertEquals(1, test.store().size());
 * }</pre>
 *
 * @see io.outbox.testing.OutboxTestSupport
 */
package io.outbox.testing;
