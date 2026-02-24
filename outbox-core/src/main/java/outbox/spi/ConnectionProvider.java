package outbox.spi;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provides JDBC connections for non-transactional outbox operations
 * (e.g. dispatcher status updates, poller queries).
 *
 * <p>Callers are responsible for closing the returned connection.
 *
 * @see outbox.jdbc.DataSourceConnectionProvider
 */
public interface ConnectionProvider {

    /**
     * Obtains a new JDBC connection.
     *
     * @return an open connection; the caller must close it
     * @throws SQLException if a connection cannot be obtained
     */
    Connection getConnection() throws SQLException;
}
