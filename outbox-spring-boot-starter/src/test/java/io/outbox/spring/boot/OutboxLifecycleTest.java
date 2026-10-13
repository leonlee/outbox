package io.outbox.spring.boot;

import io.outbox.AggregateType;
import io.outbox.BoundEventListener;
import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.Outbox;
import io.outbox.OutboxWriter;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxLifecycleTest {

    private static final io.outbox.spi.ConnectionProvider STUB_CP = () -> {
        throw new SQLException("stub");
    };
    private static final TxContext STUB_TX = new TxContext() {
        @Override
        public boolean isTransactionActive() {
            return false;
        }

        @Override
        public Connection currentConnection() {
            throw new IllegalStateException("stub");
        }

        @Override
        public void afterCommit(Runnable action) {
        }

        @Override
        public void afterRollback(Runnable action) {
        }
    };
    private static final OutboxStore STUB_STORE = new OutboxStore() {
        @Override
        public void insertNew(Connection c, EventEnvelope e) {
        }

        @Override
        public int markDone(Connection c, String id) {
            return 0;
        }

        @Override
        public int markRetry(Connection c, String id, Instant a, String e) {
            return 0;
        }

        @Override
        public int markDead(Connection c, String id, String e) {
            return 0;
        }

        @Override
        public List<io.outbox.model.OutboxEvent> pollPending(Connection c, Instant n, Duration s, int l) {
            return List.of();
        }
    };

    @Test
    void startDelegatesAndSetsRunning() {
        Outbox outbox = Outbox.singleNode()
                .connectionProvider(STUB_CP).txContext(STUB_TX)
                .outboxStore(STUB_STORE).listenerRegistry(new DefaultListenerRegistry())
                .workerCount(0).intervalMs(60_000)
                .deferStart(true)
                .build();

        var lifecycle = new OutboxLifecycle(outbox);
        assertFalse(lifecycle.isRunning());

        lifecycle.start();
        assertTrue(lifecycle.isRunning());

        lifecycle.stop();
        assertFalse(lifecycle.isRunning());

        outbox.close();
    }

    /**
     * Shutdown runs highest phase first. A producer at the web server's graceful-shutdown phase
     * that commits an event while it drains must still find the dispatcher running, or the event
     * misses the hot path (and in multi-node waits out the lock timeout under this node's stamp).
     */
    @Test
    void theOutboxStopsAfterAProducerDrainingAtTheWebServerPhase() throws InterruptedException {
        DrainingProducer.DELIVERED.set(new CountDownLatch(1));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        OutboxAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:outbox_lifecycle_order;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver")
                .withUserConfiguration(DrainingProducer.class)
                .run(ctx -> assertNull(ctx.getStartupFailure()));
        assertTrue(DrainingProducer.DELIVERED.get().await(5, TimeUnit.SECONDS),
                "an event committed while the web server drains must still be dispatched");
    }

    @Configuration(proxyBeanMethods = false)
    static class DrainingProducer {
        /** WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE (not on this classpath). */
        static final int WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 1024;
        static final AtomicReference<CountDownLatch> DELIVERED = new AtomicReference<>();

        @Bean
        BoundEventListener drainListener() {
            return new BoundEventListener(AggregateType.GLOBAL.name(), "DrainEvent") {
                @Override
                public DispatchResult onEvent(EventEnvelope event) {
                    DELIVERED.get().countDown();
                    return DispatchResult.done();
                }
            };
        }

        @Bean
        SmartLifecycle producer(OutboxWriter writer, DataSource dataSource) {
            return new SmartLifecycle() {
                private volatile boolean running;

                @Override
                public void start() {
                    try (Connection conn = dataSource.getConnection(); var st = conn.createStatement()) {
                        st.execute(new String(OutboxLifecycleTest.class.getResourceAsStream("/schema.sql")
                                .readAllBytes(), StandardCharsets.UTF_8));
                    } catch (SQLException | IOException e) {
                        throw new IllegalStateException(e);
                    }
                    running = true;
                }

                @Override
                public void stop() {
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                            .execute(status -> writer.write(EventEnvelope.builder("DrainEvent").payloadJson("{}").build()));
                    running = false;
                }

                @Override
                public boolean isRunning() {
                    return running;
                }

                @Override
                public int getPhase() {
                    return WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE;
                }
            };
        }
    }
}
