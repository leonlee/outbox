/**
 * Spring transaction integration for the outbox framework.
 *
 * <p>{@link io.outbox.spring.SpringTxContext} bridges Spring's transaction synchronization
 * with the outbox {@link io.outbox.spi.TxContext} SPI, enabling after-commit hot-path delivery
 * within Spring-managed transactions.
 *
 * @see io.outbox.spring.SpringTxContext
 */
package io.outbox.spring;
