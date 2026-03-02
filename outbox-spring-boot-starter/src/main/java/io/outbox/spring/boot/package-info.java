/**
 * Spring Boot auto-configuration for the outbox framework.
 *
 * <p>{@link io.outbox.spring.boot.OutboxAutoConfiguration} wires an {@link io.outbox.Outbox}
 * instance from {@code outbox.*} application properties. Supports all four modes:
 * {@code single-node}, {@code multi-node}, {@code ordered}, and {@code writer-only}.
 *
 * <p>Use {@link io.outbox.spring.boot.OutboxListener @OutboxListener} on bean methods
 * to register event listeners declaratively.
 *
 * @see io.outbox.spring.boot.OutboxAutoConfiguration
 * @see io.outbox.spring.boot.OutboxProperties
 * @see io.outbox.spring.boot.OutboxListener
 * @see io.outbox.spring.boot.OutboxListenerRegistrar
 */
package io.outbox.spring.boot;
