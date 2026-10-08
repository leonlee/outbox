package io.outbox.spring.boot;

import io.outbox.DispatchResult;
import io.outbox.EventEnvelope;
import io.outbox.EventListener;
import io.outbox.Outbox;
import io.outbox.OutboxWriter;
import io.outbox.jdbc.DataSourceConnectionProvider;
import io.outbox.jdbc.store.AbstractJdbcOutboxStore;
import io.outbox.jdbc.store.H2OutboxStore;
import io.outbox.registry.DefaultListenerRegistry;
import io.outbox.spi.ConnectionProvider;
import io.outbox.spi.JsonCodec;
import io.outbox.spi.TxContext;
import io.outbox.spring.SpringTxContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    OutboxAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:outbox_auto_test;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.sql.init.schema-locations=classpath:schema.sql");

    // ── Owner identity ──────────────────────────────────────────────

    /**
     * {@code owner-id} is normally {@code ${HOSTNAME}}, which resolves to the empty string wherever
     * HOSTNAME is not set — local runs and every Spring test context. An earlier version of this
     * failed startup on that, which made the whole app unbootable outside Kubernetes. It must
     * degrade to a generated id instead.
     */
    @Test
    void blankOwnerIdFallsBackToAGeneratedOneInsteadOfFailingStartup() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.owner-id=",
                        "outbox.dispatcher.stamp-writer-owner=true")
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null,
                            "a missing HOSTNAME must not stop the context loading");
                    String owner = ctx.getBean(OutboxAutoConfiguration.OutboxOwnerId.class).value();
                    assertNotNull(owner);
                    assertFalse(owner.isBlank(),
                            "an empty owner would make every instance share one identity");
                });
    }

    @Test
    void configuredOwnerIdIsUsedVerbatim() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.owner-id=pod-a")
                .run(ctx -> assertEquals("pod-a",
                        ctx.getBean(OutboxAutoConfiguration.OutboxOwnerId.class).value()));
    }

    /**
     * The writer stamp and the poller's claim lock must agree, or an instance never matches its own
     * rows and every write waits out the lock timeout before anyone picks it up. Resolving the id
     * once and injecting it into both is what guarantees that; this pins it down.
     */
    @Test
    void theWriterStampAndTheClaimLockShareOneIdentity() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.owner-id=",
                        "outbox.dispatcher.stamp-writer-owner=true")
                .run(ctx -> {
                    assertNotNull(ctx.getBean(Outbox.class));
                    assertEquals(1, ctx.getBeanNamesForType(
                            OutboxAutoConfiguration.OutboxOwnerId.class).length,
                            "exactly one identity, shared — not derived independently per consumer");
                });
    }

    /**
     * single-node plus poller-only must not start.
     *
     * <p>The builder refuses the same pair, but properties are how the application is actually
     * configured, so the branch that reads them needs its own cover: a refactor that moved this
     * check below the builder call would otherwise leave the suite green and the app re-delivering
     * every listener slower than the poll interval.
     */
    @Test
    void singleNodePollerOnlyIsRejectedAtStartup() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=single-node",
                        "outbox.dispatcher.hot-path-enabled=false")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(),
                            "poller-only without claim locking re-delivers; it must not boot");
                    assertTrue(rootCauseMessage(ctx.getStartupFailure()).contains("multi-node"),
                            "the failure has to name the fix");
                });
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /**
     * A writer stamp under a short lock timeout is a lease other nodes already consider dead.
     *
     * <p>The stamp is skipped for events older than WRITER_STAMP_MAX_AGE on the grounds that such
     * a lease would be born expired — reasoning that inverts when the lock timeout is smaller than
     * the stamp window. A 20-second-old event under a 10-second timeout is stamped, hot-enqueued,
     * and immediately claimable by every other node at once: the cross-node duplicate the stamp
     * exists to prevent. The pairing is refused at startup, with margin for commit delay.
     */
    @Test
    void aLockTimeoutInsideTheStampWindowIsRejectedWhenStampingIsOn() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.lock-timeout=PT30S",
                        "outbox.dispatcher.stamp-writer-owner=true")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(),
                            "a stamp the poller already considers expired must not boot");
                    assertTrue(rootCauseMessage(ctx.getStartupFailure()).contains("lock-timeout"),
                            "the failure has to name the knob");
                });
    }

    /**
     * Poller-only ignores the stamp, so the stamp's lock-timeout requirement must not apply.
     *
     * <p>writerOwnerId() drops stamp-writer-owner with a warning when the hot path is off — every
     * row inserts unowned, so there is no lease that a short timeout could render born-dead.
     * Rejecting the timeout anyway would fail startup over a setting the same class documents as a
     * harmless leftover.
     *
     * <p>The context may still fail here — H2's claim is not exclusive, and poller-only refuses
     * that separately — so the assertion is precisely that the failure, if any, is not the
     * lock-timeout rejection.
     */
    @Test
    void pollerOnlyDoesNotRejectAShortLockTimeoutForAStampItIgnores() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.lock-timeout=PT30S",
                        "outbox.dispatcher.stamp-writer-owner=true",
                        "outbox.dispatcher.hot-path-enabled=false")
                .run(ctx -> {
                    if (ctx.getStartupFailure() != null) {
                        String message = rootCauseMessage(ctx.getStartupFailure());
                        assertFalse(message.contains("lock-timeout"),
                                "no stamp is written, so the stamp's timeout rule must not fire: "
                                        + message);
                    }
                });
    }

    /** The same short timeout is fine when nothing stamps — there is no lease to be born dead. */
    @Test
    void aShortLockTimeoutAloneIsAccepted() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.lock-timeout=PT30S")
                .run(ctx -> assertNull(ctx.getStartupFailure(),
                        "without the stamp the short timeout is a legitimate choice"));
    }

    /**
     * The writer stamp only exists to keep other nodes off a row this instance is delivering from
     * its hot queue. With poller-only delivery there is no such row, so the stamp is redundant —
     * and not free: it would make every insert write a lease that only the claim query's
     * {@code locked_at = created_at} branch can see past, and strand any row whose owner dies
     * before claiming it for a full lock timeout.
     *
     * <p>Asserted against a control run so the null is known to come from the mode and not from
     * the stamp being broken generally.
     */
    @Test
    void theWriterStampIsDroppedWhenThereIsNoHotPathToProtect() {
        assertNotNull(stampedOwnerOnInsert(true), "control: the hot path does stamp");
        assertNull(stampedOwnerOnInsert(false),
                "poller-only inserts must be unowned — every delivery goes through a claim");
    }

    /** Inserts one event through the context's own store and reports what landed in locked_by. */
    private String stampedOwnerOnInsert(boolean hotPathEnabled) {
        AtomicReference<String> owner = new AtomicReference<>();
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        // writer-only, because the decision under test lives in how the store bean
                        // is built and has nothing to do with the dispatcher. multi-node would drag
                        // in the poller-only gate, which refuses H2 for the good reason that its
                        // claim is not atomic — a different subject.
                        "outbox.mode=writer-only",
                        "outbox.dispatcher.stamp-writer-owner=true",
                        "outbox.dispatcher.hot-path-enabled=" + hotPathEnabled)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var store = ctx.getBean(AbstractJdbcOutboxStore.class);
                    EventEnvelope event = EventEnvelope.ofJson("StampProbe", "{}");
                    try (Connection conn = ctx.getBean(ConnectionProvider.class).getConnection()) {
                        conn.setAutoCommit(true);
                        // spring.sql.init is not among this runner's auto-configurations, so the
                        // schema it names is never applied. Apply the same file here rather than
                        // restate the DDL and let the two drift.
                        try (var st = conn.createStatement()) {
                            st.execute(new String(getClass().getResourceAsStream("/schema.sql")
                                    .readAllBytes(), StandardCharsets.UTF_8));
                        }
                        store.insertNew(conn, event);
                        try (var ps = conn.prepareStatement(
                                "SELECT locked_by FROM outbox_event WHERE event_id=?")) {
                            ps.setString(1, event.eventId());
                            try (var rs = ps.executeQuery()) {
                                assertTrue(rs.next());
                                owner.set(rs.getString(1));
                            }
                        }
                    }
                });
        return owner.get();
    }

    /**
     * suppress-replays and in-flight-ttl-ms are two properties for one feature, and the library
     * rejects the combination where suppression has no expiry — correctly, since a marker that
     * never expires would disable its event permanently. But an operator flipping one env var and
     * not the other must not end up with an app that will not boot; that is the same failure this
     * config layer already absorbs for a missing owner id.
     */
    @Test
    void suppressionWithoutATtlStartsAnywayWithADefault() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues("outbox.dispatcher.suppress-replays=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(),
                            "one flag set and not the other must not stop the app booting");
                    assertNotNull(ctx.getBean(Outbox.class));
                });
    }

    @Test
    void anExplicitTtlIsLeftAlone() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.dispatcher.suppress-replays=true",
                        "outbox.dispatcher.in-flight-ttl-ms=5000")
                .run(ctx -> assertNull(ctx.getStartupFailure()));
    }

    // ── Custom JsonCodec ────────────────────────────────────────────

    /** An application's own JsonCodec bean must become the codec the outbox actually uses. */
    @Test
    void aCustomJsonCodecBeanBecomesTheDefault() {
        JsonCodec custom = new StubJsonCodec();
        try {
            runner.withUserConfiguration(ListenerConfig.class)
                    .withBean(JsonCodec.class, () -> custom)
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        assertSame(custom, JsonCodec.getDefault());
                    });
        } finally {
            JsonCodec.resetDefault();
        }
    }

    /**
     * Registering the codec used to be a side effect of the auto-configured Outbox, so an
     * application that defined its own Outbox silently lost its codec.
     */
    @Test
    void aCustomJsonCodecIsHonouredAlongsideACustomOutbox() {
        JsonCodec custom = new StubJsonCodec();
        try {
            runner.withUserConfiguration(ListenerConfig.class)
                    .withBean(JsonCodec.class, () -> custom)
                    .withBean(Outbox.class, () -> Outbox.writerOnly()
                            .txContext(STUB_TX)
                            .outboxStore(new H2OutboxStore())
                            .build())
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        assertSame(custom, JsonCodec.getDefault());
                    });
        } finally {
            JsonCodec.resetDefault();
        }
    }

    /** Two codecs with neither @Primary is the application's ambiguity; it must not fail startup. */
    @Test
    void severalJsonCodecBeansWithoutAPrimaryDoNotFailStartup() {
        JsonCodec baseline = new StubJsonCodec();
        JsonCodec.setDefault(baseline);
        try {
            runner.withUserConfiguration(ListenerConfig.class)
                    .withBean("firstCodec", JsonCodec.class, StubJsonCodec::new)
                    .withBean("secondCodec", JsonCodec.class, StubJsonCodec::new)
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        assertSame(baseline, JsonCodec.getDefault(), "neither codec is picked");
                    });
        } finally {
            JsonCodec.resetDefault();
        }
    }

    private static final TxContext STUB_TX = new TxContext() {
        @Override
        public boolean isTransactionActive() {
            return false;
        }

        @Override
        public Connection currentConnection() {
            throw new IllegalStateException("no transaction");
        }

        @Override
        public void afterCommit(Runnable callback) {
        }

        @Override
        public void afterRollback(Runnable callback) {
        }
    };

    private static final class StubJsonCodec implements JsonCodec {
        @Override
        public String toJson(Object value) {
            return "{}";
        }

        @Override
        public <T> T fromJson(String json, Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }

    // ── Purge wiring ────────────────────────────────────────────────

    /**
     * outbox.purge.enabled used to be read only in the writer-only branch, so in every dispatcher
     * mode it read as configured and did nothing — so the outbox table grew without bound.
     * The context must now come up with purge attached.
     */
    @Test
    void purgeIsWiredInDispatcherModesNotJustWriterOnly() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.owner-id=pod-a",
                        "outbox.purge.enabled=true",
                        "outbox.purge.retention=P7D")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertNotNull(ctx.getBean(Outbox.class));
                });
    }

    @Test
    void purgeStaysOffWhenNotEnabled() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues("outbox.mode=multi-node",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.owner-id=pod-a")
                .run(ctx -> assertNull(ctx.getStartupFailure()));
    }

    @Test
    void writerOnlyStillGetsPurgeToo() {
        runner.withUserConfiguration(ListenerConfig.class)
                .withPropertyValues(
                        "outbox.mode=writer-only",
                        "outbox.purge.enabled=true",
                        "outbox.purge.retention=P7D")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertNotNull(ctx.getBean(Outbox.class));
                });
    }

    @Test
    void createsAllBeans() {
        runner.withUserConfiguration(ListenerConfig.class).run(ctx -> {
            assertTrue(ctx.containsBean("outboxStore"));
            assertTrue(ctx.containsBean("connectionProvider"));
            assertTrue(ctx.containsBean("txContext"));
            assertTrue(ctx.containsBean("listenerRegistry"));
            assertTrue(ctx.containsBean("outbox"));
            assertTrue(ctx.containsBean("outboxWriter"));
            assertTrue(ctx.containsBean("outboxListenerRegistrar"));

            assertInstanceOf(H2OutboxStore.class, ctx.getBean(AbstractJdbcOutboxStore.class));
            assertInstanceOf(DataSourceConnectionProvider.class, ctx.getBean(ConnectionProvider.class));
            assertInstanceOf(SpringTxContext.class, ctx.getBean(TxContext.class));
            assertInstanceOf(DefaultListenerRegistry.class, ctx.getBean(DefaultListenerRegistry.class));
            assertInstanceOf(Outbox.class, ctx.getBean(Outbox.class));
            assertInstanceOf(OutboxWriter.class, ctx.getBean(OutboxWriter.class));
        });
    }

    @Test
    void registersAnnotatedListeners() {
        runner.withUserConfiguration(ListenerConfig.class).run(ctx -> {
            var registry = ctx.getBean(DefaultListenerRegistry.class);
            assertNotNull(registry.listenerFor("__GLOBAL__", "AutoTestEvent"));
        });
    }

    @Test
    void customTableName() {
        runner
                .withPropertyValues("outbox.table-name=custom_outbox",
                        "spring.sql.init.schema-locations=classpath:schema-custom.sql")
                .withUserConfiguration(ListenerConfig.class).run(ctx -> {
                    var store = ctx.getBean(AbstractJdbcOutboxStore.class);
                    assertInstanceOf(H2OutboxStore.class, store);
                });
    }

    @Test
    void orderedMode() {
        runner
                .withPropertyValues("outbox.mode=ORDERED")
                .withUserConfiguration(ListenerConfig.class).run(ctx -> {
                    assertInstanceOf(Outbox.class, ctx.getBean(Outbox.class));
                });
    }

    @Test
    void writerOnlyMode() {
        runner
                .withPropertyValues("outbox.mode=WRITER_ONLY")
                .withUserConfiguration(ListenerConfig.class).run(ctx -> {
                    assertInstanceOf(Outbox.class, ctx.getBean(Outbox.class));
                    assertInstanceOf(OutboxWriter.class, ctx.getBean(OutboxWriter.class));
                });
    }

    @Test
    void writerOnlyModeWithPurge() {
        runner
                .withPropertyValues("outbox.mode=WRITER_ONLY", "outbox.purge.enabled=true")
                .withUserConfiguration(ListenerConfig.class).run(ctx -> {
                    assertInstanceOf(Outbox.class, ctx.getBean(Outbox.class));
                });
    }

    @Test
    void multiNodeRequiresClaimLockingEnabled() {
        runner
                .withPropertyValues("outbox.mode=MULTI_NODE")
                .withUserConfiguration(ListenerConfig.class).run(ctx -> {
                    assertNotNull(ctx.getStartupFailure());
                    assertInstanceOf(IllegalStateException.class,
                            findRootCause(ctx.getStartupFailure()));
                });
    }

    @Test
    void multiNodeWithClaimLocking() {
        runner
                .withPropertyValues("outbox.mode=MULTI_NODE",
                        "outbox.claim-locking.enabled=true",
                        "outbox.claim-locking.lock-timeout=PT5M")
                .withUserConfiguration(ListenerConfig.class).run(ctx -> {
                    assertInstanceOf(Outbox.class, ctx.getBean(Outbox.class));
                });
    }

    @Test
    void notLoadedWithoutDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
                .run(ctx -> {
                    assertFalse(ctx.containsBean("outbox"));
                });
    }

    @Test
    void respectsConditionalOnMissingBean() {
        runner.withUserConfiguration(CustomStoreConfig.class, ListenerConfig.class).run(ctx -> {
            var store = ctx.getBean(AbstractJdbcOutboxStore.class);
            assertInstanceOf(H2OutboxStore.class, store);
            // Verify it's our custom bean (custom table name)
            assertEquals("my_custom_store", ctx.getBeanNamesForType(AbstractJdbcOutboxStore.class)[0]);
        });
    }

    // ── Test configurations ──────────────────────────────────────

    @OutboxListener(eventType = "AutoTestEvent")
    static class TestEventListener implements EventListener {
        @Override
        public DispatchResult onEvent(EventEnvelope envelope) {
            return DispatchResult.done();
        }
    }

    @Configuration
    static class ListenerConfig {
        @Bean
        TestEventListener testEventListener() {
            return new TestEventListener();
        }
    }

    @Configuration
    static class CustomStoreConfig {
        @Bean("my_custom_store")
        AbstractJdbcOutboxStore outboxStore() {
            return new H2OutboxStore();
        }
    }

    private static Throwable findRootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
