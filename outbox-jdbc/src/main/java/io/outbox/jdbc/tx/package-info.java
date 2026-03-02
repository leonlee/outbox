/**
 * Manual JDBC transaction management.
 *
 * <p>{@link io.outbox.jdbc.tx.JdbcTransactionManager} provides a lightweight
 * try-with-resources API for managing transactions with
 * {@link io.outbox.jdbc.tx.ThreadLocalTxContext}.
 *
 * @see io.outbox.jdbc.tx.JdbcTransactionManager
 * @see io.outbox.jdbc.tx.ThreadLocalTxContext
 */
package io.outbox.jdbc.tx;
