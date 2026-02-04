package outbox.demo.spring;

import outbox.core.api.OutboxClient;
import outbox.core.api.OutboxMetrics;
import outbox.core.client.DefaultOutboxClient;
import outbox.core.dispatch.DefaultInFlightTracker;
import outbox.core.dispatch.Dispatcher;
import outbox.core.dispatch.ExponentialBackoffRetryPolicy;
import outbox.core.poller.OutboxPoller;
import outbox.core.registry.DefaultListenerRegistry;
import outbox.core.registry.ListenerRegistry;
import outbox.core.tx.TxContext;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.JdbcOutboxRepository;
import outbox.spring.SpringTxContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

  private static final Logger log = LoggerFactory.getLogger(OutboxConfiguration.class);

  @Bean
  public JdbcOutboxRepository outboxRepository() {
    return new JdbcOutboxRepository();
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
        .register("UserCreated", event -> {
          log.info("[Listener] UserCreated: id={}, payload={}",
              event.eventId(), event.payloadJson());
        })
        .register("OrderPlaced", event -> {
          log.info("[Listener] OrderPlaced: id={}, payload={}",
              event.eventId(), event.payloadJson());
        })
        .registerAll(event -> {
          log.info("[Audit] Event dispatched: type={}, aggregateId={}",
              event.eventType(), event.aggregateId());
        });
  }

  @Bean
  public Dispatcher dispatcher(
      DataSourceConnectionProvider connectionProvider,
      JdbcOutboxRepository repository,
      ListenerRegistry listenerRegistry
  ) {
    return new Dispatcher(
        connectionProvider,
        repository,
        listenerRegistry,
        new DefaultInFlightTracker(30_000),
        new ExponentialBackoffRetryPolicy(200, 60_000),
        10,   // maxAttempts
        2,    // workerCount
        1000, // hotQueueCapacity
        1000, // coldQueueCapacity
        OutboxMetrics.NOOP
    );
  }

  @Bean(destroyMethod = "close")
  public OutboxPoller outboxPoller(
      DataSourceConnectionProvider connectionProvider,
      JdbcOutboxRepository repository,
      Dispatcher dispatcher
  ) {
    OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ofMillis(500),
        100,
        5000,
        OutboxMetrics.NOOP
    );
    poller.start();
    log.info("OutboxPoller started");
    return poller;
  }

  @Bean
  public OutboxClient outboxClient(
      TxContext txContext,
      JdbcOutboxRepository repository,
      Dispatcher dispatcher
  ) {
    return new DefaultOutboxClient(txContext, repository, dispatcher, OutboxMetrics.NOOP);
  }
}
