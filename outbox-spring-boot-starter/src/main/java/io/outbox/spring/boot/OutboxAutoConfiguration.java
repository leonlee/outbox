package io.outbox.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.outbox.Outbox;
import io.outbox.OutboxWriter;
import io.outbox.dispatch.EventInterceptor;
import io.outbox.dispatch.ExponentialBackoffRetryPolicy;
import io.outbox.dispatch.RetryPolicy;
import io.outbox.jdbc.DataSourceConnectionProvider;
import io.outbox.jdbc.TableNames;
import io.outbox.jdbc.purge.H2EventPurger;
import io.outbox.jdbc.purge.MySqlEventPurger;
import io.outbox.jdbc.purge.PostgresEventPurger;
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
import io.outbox.spi.JsonCodec;
import io.outbox.spi.MetricsExporter;
import io.outbox.spi.OutboxStore;
import io.outbox.spi.TxContext;
import io.outbox.spring.SpringTxContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(OutboxAutoConfiguration.class.getName());

    /**
     * TTL applied when replay suppression is switched on without one.
     *
     * <p>Suppression cannot work without expiry — a settled marker would be held forever and its
     * event could never be dispatched again — so the library refuses the combination outright. That
     * is the right answer for a caller wiring the dispatcher by hand, but not for a property: the
     * app would simply fail to start because somebody set one flag and not the other. Configuration
     * fills the gap and says so, the same way a missing owner id does.
     */
    private static final long DEFAULT_IN_FLIGHT_TTL_MS = 60_000L;

    @Bean
    @ConditionalOnMissingBean(JsonCodec.class)
    @ConditionalOnBean(ObjectMapper.class)
    public JacksonJsonCodec jacksonJsonCodec(ObjectMapper objectMapper) {
        JacksonJsonCodec codec = new JacksonJsonCodec(objectMapper);
        JsonCodec.setDefault(codec);
        return codec;
    }

    /**
     * Makes the application's own {@link JsonCodec} bean the global default that the stores and
     * {@code EventEnvelope.payload()} use — the only one, or the {@code @Primary} one. Without
     * this a custom codec bean is silently ignored: the Jackson codec above backs off for it, and
     * the default falls through to {@code ServiceLoader}.
     *
     * <p>Applied when the first outbox store, {@code Outbox} or {@code OutboxWriter} bean is
     * initialised, whether the application defines it or this configuration does. Nothing can
     * write without one of those, so the codec is in place before any bean can write, even from
     * its own initialisation. Several codec beans with none {@code @Primary} are reported, once,
     * instead of failing startup over a choice the framework cannot make.
     *
     * <p>An application that builds its {@code Outbox} around a store that is not a bean, and
     * starts it inside the factory method, should call {@link JsonCodec#setDefault} itself.
     *
     * @param codecs the application's JsonCodec beans, if any
     * @return the registrar
     */
    @Bean
    public static BeanPostProcessor outboxJsonCodecRegistrar(ObjectProvider<JsonCodec> codecs) {
        AtomicBoolean applied = new AtomicBoolean();
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if ((bean instanceof OutboxStore || bean instanceof Outbox || bean instanceof OutboxWriter)
                        && applied.compareAndSet(false, true)) {
                    useApplicationJsonCodec(codecs);
                }
                return bean;
            }
        };
    }

    private static void useApplicationJsonCodec(ObjectProvider<JsonCodec> codecs) {
        JsonCodec codec = codecs.getIfUnique();
        if (codec != null) {
            JsonCodec.setDefault(codec);
        } else if (codecs.stream().findAny().isPresent()) {
            LOGGER.warning("Several JsonCodec beans and none is @Primary, so the outbox keeps its "
                    + "default codec. Mark the one it should use @Primary.");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public AbstractJdbcOutboxStore outboxStore(DataSource dataSource, OutboxProperties props,
                                              OutboxOwnerId ownerId) {
        String tableName = props.getTableName();
        String writerOwnerId = writerOwnerId(props, ownerId);
        AbstractJdbcOutboxStore detected = JdbcOutboxStores.detect(dataSource);
        if (writerOwnerId == null && TableNames.DEFAULT_TABLE.equals(tableName)) {
            return detected;
        }
        return switch (detected.name()) {
            case "h2" -> new H2OutboxStore(tableName, writerOwnerId);
            case "mysql" -> new MySqlOutboxStore(tableName, writerOwnerId);
            case "postgresql" -> new PostgresOutboxStore(tableName, writerOwnerId);
            default -> detected;
        };
    }

    /**
     * The owner stamped into {@code locked_by} at INSERT, or {@code null} to insert unowned rows.
     *
     * <p>The stamp exists so that, while this instance delivers an event from its hot queue, no
     * other node's poller claims the same row. With the hot path off there is no such delivery to
     * protect: the row is only ever reached by a claim, and every claim is already exclusive. A
     * stamp would then be pure cost — it makes every insert write a lease that the claim query has
     * to recognise through its {@code locked_at = created_at} branch, and any row whose owner dies
     * before it is claimed waits out the lock timeout for nothing.
     *
     * <p>Ignored rather than rejected: this is a redundant setting, not a dangerous one, and
     * failing startup over it would turn a harmless leftover in a yaml into an outage. It is logged
     * so it does not become the kind of silently-dead flag this codebase has been bitten by.
     */
    private static String writerOwnerId(OutboxProperties props, OutboxOwnerId ownerId) {
        if (!props.getDispatcher().isStampWriterOwner()) {
            return null;
        }
        if (!props.getDispatcher().isHotPathEnabled()) {
            LOGGER.warning("outbox.dispatcher.stamp-writer-owner=true is ignored while "
                    + "hot-path-enabled=false: with poller-only delivery there is no hot copy for "
                    + "the stamp to protect. Events will be inserted with locked_by=NULL.");
            return null;
        }
        return ownerId.value();
    }

    /**
     * The one identity this instance uses everywhere it needs to say "mine".
     *
     * <p>Both the poller's claim lock and the writer's ownership stamp key off it, and they
     * <b>must agree</b>: if the stamp said one thing and the claim another, an instance would
     * never match its own rows and every write would sit unclaimed until the lock timeout. So it
     * is resolved once, here, and injected into both rather than derived twice.
     *
     * <p>{@code owner-id} is normally {@code ${HOSTNAME}}, which Kubernetes always sets to the pod
     * name. Where it is not set — local runs, the test context — the property resolves to the
     * empty string, and an empty owner is worse than no owner: every instance would stamp and
     * match {@code ""}, so partitioning would be silently off AND {@code locked_by IS NULL} would
     * never hold again. Rather than fail startup for that (which breaks every environment without
     * a HOSTNAME), fall back to a per-JVM random id, which satisfies the only real requirement:
     * unique per running instance.
     *
     * <p>The cost of the fallback is the same as a pod restart under the normal configuration —
     * the previous identity's un-dispatched rows wait out the lock timeout before anyone reclaims
     * them. Bounded, and only on a restart.
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxOwnerId outboxOwnerId(OutboxProperties props) {
        String configured = props.getClaimLocking().getOwnerId();
        if (configured != null && !configured.isBlank()) {
            return new OutboxOwnerId(configured);
        }
        String generated = "outbox-" + UUID.randomUUID();
        LOGGER.warning("outbox.claim-locking.owner-id is not set (HOSTNAME missing?); using a "
                + "generated per-JVM id " + generated + ". Set HOSTNAME in any environment where "
                + "instances must keep a stable identity across restarts.");
        return new OutboxOwnerId(generated);
    }

    /**
     * Attaches a purger when {@code outbox.purge.enabled} is set, choosing the implementation that
     * matches how events reach a terminal state in this mode.
     *
     * <p>The distinction is not cosmetic. With a dispatcher running, events end up
     * {@code DONE}/{@code DEAD} and only those may be deleted — that is
     * {@code AbstractJdbcEventPurger}. In writer-only (CDC) mode nothing marks them, so age is the
     * only safe criterion and {@code AbstractJdbcAgeBasedPurger} is correct. Using the age-based
     * one where a dispatcher IS running would delete PENDING, i.e. undelivered, events.
     *
     * <p>Until now this ran only in the writer-only branch, so {@code outbox.purge.enabled} was
     * silently ignored in every dispatcher mode — the flag read as configured and did nothing,
     * so the outbox table grew without bound.
     */
    private static void applyPurge(Outbox.AbstractBuilder<?> builder, OutboxProperties props,
                                   AbstractJdbcOutboxStore outboxStore, boolean writerOnly) {
        if (!props.getPurge().isEnabled()) {
            return;
        }
        String tableName = props.getTableName();
        EventPurger purger = writerOnly
                ? createAgeBasedPurger(outboxStore.name(), tableName)
                : createEventPurger(outboxStore.name(), tableName);
        builder.purger(purger)
                .purgeRetention(props.getPurge().getRetention())
                .purgeBatchSize(props.getPurge().getBatchSize())
                .purgeIntervalSeconds(props.getPurge().getIntervalSeconds());
    }

    /** Status-filtered purger: deletes only DONE / DEAD rows. For modes with a dispatcher. */
    private static EventPurger createEventPurger(String dbName, String tableName) {
        return switch (dbName) {
            case "h2" -> new H2EventPurger(tableName);
            case "mysql" -> new MySqlEventPurger(tableName);
            case "postgresql" -> new PostgresEventPurger(tableName);
            default -> throw new IllegalStateException(
                    "No status-filtered purger for store '" + dbName + "'; "
                            + "set outbox.purge.enabled=false or supply an EventPurger bean");
        };
    }

    /**
     * The in-flight TTL to use, filling one in when suppression needs one and none was given.
     *
     * <p>Sizing, for anyone changing it: the TTL has to outlive the longest gap between an event
     * being picked up and reaching a terminal state, because that is how long the entry has to
     * survive to be worth anything — and it must also outlive the slowest LISTENER, or an entry can
     * expire while its listener is still running and a second copy will be let through. The
     * hot-path breaker bounds the queueing half; the listener half is on whoever writes the
     * listener. Size it well above your slowest listener; the default is 60s.
     */
    private static long inFlightTtlMs(OutboxProperties props) {
        long configured = props.getDispatcher().getInFlightTtlMs();
        if (configured > 0 || !props.getDispatcher().isSuppressReplays()) {
            return configured;
        }
        LOGGER.warning("outbox.dispatcher.suppress-replays is on without an in-flight-ttl-ms; "
                + "defaulting to " + DEFAULT_IN_FLIGHT_TTL_MS + "ms. Suppression needs entries to "
                + "expire, and the TTL must exceed your slowest listener.");
        return DEFAULT_IN_FLIGHT_TTL_MS;
    }

    /** Wrapper so the resolved owner id can be injected as a bean without colliding with String. */
    public record OutboxOwnerId(String value) {
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
                         OutboxOwnerId ownerId,
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
                if (!props.getDispatcher().isHotPathEnabled()) {
                    // Delivered exclusively by an unlocked poller, a listener that outlives the
                    // poll interval is handed the same row on every poll until it finishes. Only a
                    // claim stops that, and single-node takes none.
                    throw new IllegalStateException(
                            "outbox.dispatcher.hot-path-enabled=false requires claim locking, which "
                                    + "outbox.mode=single-node does not use. Set outbox.mode=multi-node "
                                    + "and outbox.claim-locking.enabled=true — correct on one node too.");
                }
                var builder = Outbox.singleNode()
                        .connectionProvider(connectionProvider)
                        .txContext(txContext)
                        .outboxStore(outboxStore)
                        .listenerRegistry(listenerRegistry)
                        .workerCount(props.getDispatcher().getWorkerCount())
                        .hotQueueCapacity(props.getDispatcher().getHotQueueCapacity())
                        .coldQueueCapacity(props.getDispatcher().getColdQueueCapacity())
                        .maxAttempts(props.getDispatcher().getMaxAttempts())
                        .deferStart(true)
                        .drainTimeoutMs(props.getDispatcher().getDrainTimeoutMs())
                        .hotTripMs(props.getDispatcher().getHotTripMs())
                        .inFlightTtlMs(inFlightTtlMs(props))
                        .suppressReplays(props.getDispatcher().isSuppressReplays())
                        .hotPathEnabled(props.getDispatcher().isHotPathEnabled())
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
                applyPurge(builder, props, outboxStore, false);
                yield builder.build();
            }
            case MULTI_NODE -> {
                if (!props.getClaimLocking().isEnabled()) {
                    throw new IllegalStateException(
                            "outbox.claim-locking.enabled must be true for multi-node mode");
                }
                if (props.getDispatcher().isStampWriterOwner()
                        && props.getDispatcher().isHotPathEnabled()
                        && props.getClaimLocking().getLockTimeout()
                                .compareTo(OutboxStore.WRITER_STAMP_MAX_AGE.multipliedBy(2)) < 0) {
                    // Only when a stamp will actually be written: with the hot path off,
                    // writerOwnerId() ignores stamp-writer-owner (with a warning) and every row
                    // inserts unowned — rejecting the timeout then would fail startup over a
                    // setting that is already documented as a harmless leftover.
                    //
                    // The stamp writes locked_at = created_at and skips events older than
                    // WRITER_STAMP_MAX_AGE, reasoning that such a lease is already expired. That
                    // reasoning inverts if the lock timeout is not comfortably larger: a stamp on
                    // an event 20s old is written under a 10s timeout as a lease every other node
                    // already considers dead, and the hot copy then races their claims — the
                    // duplicate the stamp exists to prevent. 2x is margin for the commit delay the
                    // insert-time check cannot see.
                    throw new IllegalStateException(
                            "outbox.dispatcher.stamp-writer-owner=true requires "
                                    + "outbox.claim-locking.lock-timeout of at least twice "
                                    + OutboxStore.WRITER_STAMP_MAX_AGE + " (got "
                                    + props.getClaimLocking().getLockTimeout() + "). Raise the "
                                    + "lock timeout or disable stamp-writer-owner.");
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
                        .deferStart(true)
                        .drainTimeoutMs(props.getDispatcher().getDrainTimeoutMs())
                        .hotTripMs(props.getDispatcher().getHotTripMs())
                        .inFlightTtlMs(inFlightTtlMs(props))
                        .suppressReplays(props.getDispatcher().isSuppressReplays())
                        .hotPathEnabled(props.getDispatcher().isHotPathEnabled())
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
                applyPurge(builder, props, outboxStore, false);
                var cl = props.getClaimLocking();
                // Same identity the writer stamps with — see outboxOwnerId().
                builder.claimLocking(ownerId.value(), cl.getLockTimeout());
                yield builder.build();
            }
            case ORDERED -> {
                var builder = Outbox.ordered()
                        .connectionProvider(connectionProvider)
                        .txContext(txContext)
                        .outboxStore(outboxStore)
                        .listenerRegistry(listenerRegistry)
                        .deferStart(true)
                        .drainTimeoutMs(props.getDispatcher().getDrainTimeoutMs())
                        .hotTripMs(props.getDispatcher().getHotTripMs())
                        .inFlightTtlMs(inFlightTtlMs(props))
                        .suppressReplays(props.getDispatcher().isSuppressReplays())
                        .intervalMs(props.getPoller().getIntervalMs())
                        .batchSize(props.getPoller().getBatchSize());
                if (props.getPoller().getSkipRecentMs() > 0) {
                    builder.skipRecent(Duration.ofMillis(props.getPoller().getSkipRecentMs()));
                }
                if (metrics != null) {
                    builder.metrics(metrics);
                }
                interceptors.forEach(builder::interceptor);
                applyPurge(builder, props, outboxStore, false);
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
                applyPurge(builder, props, outboxStore, true);
                yield builder.build();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(Outbox outbox) {
        return outbox.writer();
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxLifecycle outboxLifecycle(Outbox outbox) {
        return new OutboxLifecycle(outbox);
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
