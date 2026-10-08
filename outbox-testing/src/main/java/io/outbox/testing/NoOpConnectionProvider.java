package io.outbox.testing;

import io.outbox.spi.ConnectionProvider;

import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * {@link ConnectionProvider} that hands out a connection whose every method does nothing.
 *
 * <p>For unit tests that use {@link InMemoryOutboxStore}, which ignores the connection
 * parameter. The dispatcher and poller still call {@code setAutoCommit}, {@code commit} and
 * {@code close} on whatever they are given, so a {@code null} connection would fail every
 * status update; this one accepts those calls and returns {@code false}, {@code 0} or
 * {@code null} from anything that has a return value.
 */
public class NoOpConnectionProvider implements ConnectionProvider {

    private static final Connection NO_OP = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "NoOpConnection";
                default -> defaultValue(method.getReturnType());
            });

    @Override
    public Connection getConnection() {
        return NO_OP;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
