package outbox.demo.spring;

import outbox.OutboxWriter;
import outbox.spi.TxContext;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.EventInterceptor;
import outbox.dispatch.DispatcherWriterHook;
import outbox.dispatch.DispatcherPollerHandler;
import outbox.dispatch.OutboxDispatcher;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.registry.ListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.store.JdbcOutboxStores;
import outbox.spring.SpringTxContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

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
