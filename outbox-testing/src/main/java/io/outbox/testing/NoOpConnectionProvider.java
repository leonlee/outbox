package io.outbox.testing;

import io.outbox.spi.ConnectionProvider;

import java.sql.Connection;

/**
 * {@link ConnectionProvider} that always returns {@code null}.
 *
 * <p>Sufficient for unit tests that use {@link InMemoryOutboxStore},
 * which ignores the connection parameter.
 */
public class NoOpConnectionProvider implements ConnectionProvider {

    @Override
    public Connection getConnection() {
        return null;
    }
}
