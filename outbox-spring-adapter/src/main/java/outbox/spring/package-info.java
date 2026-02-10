/**
 * Spring transaction integration for the outbox framework.
 *
 * <p>{@link outbox.spring.SpringTxContext} bridges Spring's transaction synchronization
 * with the outbox {@link outbox.spi.TxContext} SPI, enabling after-commit hot-path delivery
 * within Spring-managed transactions.
 *
 * @see outbox.spring.SpringTxContext
 */
package outbox.spring;
