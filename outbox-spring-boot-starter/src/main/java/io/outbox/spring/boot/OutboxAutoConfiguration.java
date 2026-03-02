package io.outbox.spring.boot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.outbox.Outbox;
import io.outbox.OutboxWriter;
import io.outbox.dispatch.EventInterceptor;
import io.outbox.dispatch.ExponentialBackoffRetryPolicy;
import io.outbox.dispatch.RetryPolicy;
import io.outbox.jdbc.DataSourceConnectionProvider;
import io.outbox.jdbc.TableNames;
import io.outbox.jdbc.purge.H2AgeBasedPurger;
import io.outbox.jdbc.purge.MySqlAgeBasedPurger;
import io.outbox.jdbc.purge.PostgresAgeBasedPurger;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.H2OutboxStore;
import io.outbox.jdbc.store.JdbcOutboxStores;
import io.outbox.jdbc.store.MySqlOutboxStore;
import io.outbox.jdbc.store.PostgresOutboxStore;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.EventPurger;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.TxContext;
import io.outbox.spring.SpringTxContext;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

/**
 * Auto-configuration for the outbox framework.
 *
 * <p>Wires up an {@link Outbox} composite from a {@link DataSource} and
 * {@link OutboxProperties}. Supports single-node, multi-node, ordered,
 * and writer-only modes.
 *
 * @see OutboxProperties
 * @see OutboxMicrometerAutoConfiguration
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(Outbox.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AbstractJdbcOutboxStore outboxStore(DataSource dataSource, OutboxProperties props) {
        String tableName = props.getTableName();
        AbstractJdbcOutboxStore detected = JdbcOutboxStores.detect(dataSource);
        if (!TableNames.DEFAULT_TABLE.equals(tableName)) {
            return switch (detected.name()) {
                case "h2" -> new H2OutboxStore(tableName);
                case "mysql" -> new MySqlOutboxStore(tableName);
                case "postgresql" -> new PostgresOutboxStore(tableName);
                default -> detected;
            };
        }
        return detected;
    }

    @Bean
    @ConditionalOnMissingBean(ConnectionProvider.class)
    public DataSourceConnectionProvider connectionProvider(DataSource dataSource) {
        return new DataSourceConnectionProvider(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(TxContext.class)
    public SpringTxContext txContext(DataSource dataSource) {
        return new SpringTxContext(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultListenerRegistry listenerRegistry() {
        return new DefaultListenerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxListenerRegistrar outboxListenerRegistrar(
            org.springframework.beans.factory.ListableBeanFactory beanFactory,
            DefaultListenerRegistry listenerRegistry) {
        return new OutboxListenerRegistrar(beanFactory, listenerRegistry);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Outbox outbox(OutboxProperties props,
                         ConnectionProvider connectionProvider,
                         TxContext txContext,
                         AbstractJdbcOutboxStore outboxStore,
                         DefaultListenerRegistry listenerRegistry,
                         ObjectProvider<MetricsExporter> metricsProvider,
                         ObjectProvider<EventInterceptor> interceptorProvider) {

        MetricsExporter metrics = metricsProvider.getIfAvailable();
        List<EventInterceptor> interceptors = interceptorProvider.orderedStream().toList();
        RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(
                props.getRetry().getBaseDelayMs(), props.getRetry().getMaxDelayMs());

        return switch (props.getMode()) {
            case SINGLE_NODE -> {
                var builder = Outbox.singleNode()
                        .connectionProvider(connectionProvider)
                        .txContext(txContext)
                        .outboxStore(outboxStore)
                        .listenerRegistry(listenerRegistry)
                        .workerCount(props.getDispatcher().getWorkerCount())
                        .hotQueueCapacity(props.getDispatcher().getHotQueueCapacity())
                        .coldQueueCapacity(props.getDispatcher().getColdQueueCapacity())
                        .maxAttempts(props.getDispatcher().getMaxAttempts())
                        .drainTimeoutMs(props.getDispatcher().getDrainTimeoutMs())
                        .retryPolicy(retryPolicy)
                        .intervalMs(props.getPoller().getIntervalMs())
                        .batchSize(props.getPoller().getBatchSize());
                if (props.getPoller().getSkipRecentMs() > 0) {
                    builder.skipRecent(Duration.ofMillis(props.getPoller().getSkipRecentMs()));
                }
                if (metrics != null) {
                    builder.metrics(metrics);
                }
                interceptors.forEach(builder::interceptor);
                yield builder.build();
            }
            case MULTI_NODE -> {
                if (!props.getClaimLocking().isEnabled()) {
                    throw new IllegalStateException(
                            "outbox.claim-locking.enabled must be true for multi-node mode");
                }
                var builder = Outbox.multiNode()
                        .connectionProvider(connectionProvider)
                        .txContext(txContext)
                        .outboxStore(outboxStore)
                        .listenerRegistry(listenerRegistry)
                        .workerCount(props.getDispatcher().getWorkerCount())
                        .hotQueueCapacity(props.getDispatcher().getHotQueueCapacity())
                        .coldQueueCapacity(props.getDispatcher().getColdQueueCapacity())
                        .maxAttempts(props.getDispatcher().getMaxAttempts())
                        .drainTimeoutMs(props.getDispatcher().getDrainTimeoutMs())
                        .retryPolicy(retryPolicy)
                        .intervalMs(props.getPoller().getIntervalMs())
                        .batchSize(props.getPoller().getBatchSize());
                if (props.getPoller().getSkipRecentMs() > 0) {
                    builder.skipRecent(Duration.ofMillis(props.getPoller().getSkipRecentMs()));
                }
                if (metrics != null) {
                    builder.metrics(metrics);
                }
                interceptors.forEach(builder::interceptor);
                var cl = props.getClaimLocking();
                if (cl.getOwnerId() != null && !cl.getOwnerId().isEmpty()) {
                    builder.claimLocking(cl.getOwnerId(), cl.getLockTimeout());
                } else {
                    builder.claimLocking(cl.getLockTimeout());
                }
                yield builder.build();
            }
            case ORDERED -> {
                var builder = Outbox.ordered()
                        .connectionProvider(connectionProvider)
                        .txContext(txContext)
                        .outboxStore(outboxStore)
                        .listenerRegistry(listenerRegistry)
                        .drainTimeoutMs(props.getDispatcher().getDrainTimeoutMs())
                        .intervalMs(props.getPoller().getIntervalMs())
                        .batchSize(props.getPoller().getBatchSize());
                if (props.getPoller().getSkipRecentMs() > 0) {
                    builder.skipRecent(Duration.ofMillis(props.getPoller().getSkipRecentMs()));
                }
                if (metrics != null) {
                    builder.metrics(metrics);
                }
                interceptors.forEach(builder::interceptor);
                yield builder.build();
            }
            case WRITER_ONLY -> {
                var builder = Outbox.writerOnly()
                        .connectionProvider(connectionProvider)
                        .txContext(txContext)
                        .outboxStore(outboxStore);
                if (metrics != null) {
                    builder.metrics(metrics);
                }
                if (props.getPurge().isEnabled()) {
                    String tableName = props.getTableName();
                    EventPurger purger = createAgeBasedPurger(outboxStore.name(), tableName);
                    builder.purger(purger)
                            .purgeRetention(props.getPurge().getRetention())
                            .purgeBatchSize(props.getPurge().getBatchSize())
                            .purgeIntervalSeconds(props.getPurge().getIntervalSeconds());
                }
                yield builder.build();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(Outbox outbox) {
        return outbox.writer();
    }

    private static EventPurger createAgeBasedPurger(String dbName, String tableName) {
        return switch (dbName) {
            case "h2" -> new H2AgeBasedPurger(tableName);
            case "mysql" -> new MySqlAgeBasedPurger(tableName);
            case "postgresql" -> new PostgresAgeBasedPurger(tableName);
            default -> throw new IllegalStateException(
                    "No age-based purger available for database: " + dbName);
        };
    }
}
