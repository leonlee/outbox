package outbox.demo.spring;

import outbox.OutboxClient;
import outbox.spi.MetricsExporter;
import outbox.spi.TxContext;
import outbox.dispatch.DefaultInFlightTracker;
import outbox.dispatch.OutboxDispatcher;
import outbox.dispatch.ExponentialBackoffRetryPolicy;
import outbox.poller.OutboxPoller;
import outbox.registry.DefaultListenerRegistry;
import outbox.registry.ListenerRegistry;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.JdbcOutboxRepository;
import outbox.jdbc.dialect.Dialects;
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
  public JdbcOutboxRepository outboxRepository(DataSource dataSource) {
    return new JdbcOutboxRepository(Dialects.detect(dataSource));
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

  @Bean(destroyMethod = "close")
  public OutboxDispatcher dispatcher(
      DataSourceConnectionProvider connectionProvider,
      JdbcOutboxRepository repository,
      ListenerRegistry listenerRegistry
  ) {
    return new OutboxDispatcher(
        connectionProvider,
        repository,
        listenerRegistry,
        new DefaultInFlightTracker(30_000),
        new ExponentialBackoffRetryPolicy(200, 60_000),
        10,   // maxAttempts
        2,    // workerCount
        1000, // hotQueueCapacity
        1000, // coldQueueCapacity
        MetricsExporter.NOOP
    );
  }

  @Bean(destroyMethod = "close")
  public OutboxPoller outboxPoller(
      DataSourceConnectionProvider connectionProvider,
      JdbcOutboxRepository repository,
      OutboxDispatcher dispatcher
  ) {
    OutboxPoller poller = new OutboxPoller(
        connectionProvider,
        repository,
        dispatcher,
        Duration.ofMillis(500),
        100,
        5000,
        MetricsExporter.NOOP
    );
    poller.start();
    log.info("OutboxPoller started");
    return poller;
  }

  @Bean
  public OutboxClient outboxClient(
      TxContext txContext,
      JdbcOutboxRepository repository,
      OutboxDispatcher dispatcher
  ) {
    return new OutboxClient(txContext, repository, dispatcher, MetricsExporter.NOOP);
  }
}
