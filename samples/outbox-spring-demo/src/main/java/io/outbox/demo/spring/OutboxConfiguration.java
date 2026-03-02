package io.outbox.demo.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import io.outbox.OutboxWriter;
import io.outbox.dispatch.DefaultInFlightTracker;
import io.outbox.dispatch.DispatcherPollerHandler;
import io.outbox.dispatch.DispatcherWriterHook;
import io.outbox.dispatch.EventInterceptor;
import io.outbox.dispatch.OutboxDispatcher;
import io.outbox.jdbc.DataSourceConnectionProvider;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.JdbcOutboxStores;
import io.outbox.poller.OutboxPoller;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.registry.ListenerRegistry;
import io.outbox.spi.TxContext;
import io.outbox.spring.SpringTxContext;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxConfiguration.class);

    @Bean
    public AbstractJdbcOutboxStore outboxStore(DataSource dataSource) {
        return JdbcOutboxStores.detect(dataSource);
    }

    @Bean
    public DataSourceConnectionProvider connectionProvider(DataSource dataSource) {
        return new DataSourceConnectionProvider(dataSource);
    }

    @Bean
    public TxContext txContext(DataSource dataSource) {
        return new SpringTxContext(dataSource);
    }

    @Bean
    public ListenerRegistry listenerRegistry() {
        return new DefaultListenerRegistry()
                .register("User", "UserCreated", event -> {
                    log.info("[Listener] User/UserCreated: id={}, payload={}",
                            event.eventId(), event.payloadJson());
                })
                .register("Order", "OrderPlaced", event -> {
                    log.info("[Listener] Order/OrderPlaced: id={}, payload={}",
                            event.eventId(), event.payloadJson());
                });
    }

    @Bean(destroyMethod = "close")
    public OutboxDispatcher dispatcher(
            DataSourceConnectionProvider connectionProvider,
            AbstractJdbcOutboxStore outboxStore,
            ListenerRegistry listenerRegistry
    ) {
        return OutboxDispatcher.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .listenerRegistry(listenerRegistry)
                .inFlightTracker(new DefaultInFlightTracker(30_000))
                .workerCount(2)
                .interceptor(EventInterceptor.before(event ->
                        log.info("[Audit] Event dispatched: type={}, aggregateId={}",
                                event.eventType(), event.aggregateId())))
                .build();
    }

    @Bean(destroyMethod = "close")
    public OutboxPoller outboxPoller(
            DataSourceConnectionProvider connectionProvider,
            AbstractJdbcOutboxStore outboxStore,
            OutboxDispatcher dispatcher
    ) {
        return OutboxPoller.builder()
                .connectionProvider(connectionProvider)
                .outboxStore(outboxStore)
                .handler(new DispatcherPollerHandler(dispatcher))
                .skipRecent(Duration.ofMillis(500))
                .batchSize(100)
                .intervalMs(5000)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(OutboxPoller.class).start();
        log.info("OutboxPoller started");
    }

    @Bean
    public OutboxWriter outboxWriter(
            TxContext txContext,
            AbstractJdbcOutboxStore outboxStore,
            OutboxDispatcher dispatcher
    ) {
        return new OutboxWriter(txContext, outboxStore, new DispatcherWriterHook(dispatcher));
    }
}
