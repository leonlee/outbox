/**
 * Manual JDBC transaction management.
 *
 * <p>{@link outbox.jdbc.tx.JdbcTransactionManager} provides a lightweight
 * try-with-resources API for managing transactions with
 * {@link outbox.jdbc.tx.ThreadLocalTxContext}.
 *
 * @see outbox.jdbc.tx.JdbcTransactionManager
 * @see outbox.jdbc.tx.ThreadLocalTxContext
 */
package outbox.jdbc.tx;
