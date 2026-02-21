package outbox.spring.boot;

import outbox.EventEnvelope;
import outbox.EventListener;
import outbox.Outbox;
import outbox.OutboxWriter;
import outbox.jdbc.DataSourceConnectionProvider;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.store.H2OutboxStore;
import outbox.registry.DefaultListenerRegistry;
import outbox.spi.ConnectionProvider;
import outbox.spi.TxContext;
import outbox.spring.SpringTxContext;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class OutboxAutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          DataSourceAutoConfiguration.class,
          OutboxAutoConfiguration.class))
      .withPropertyValues(
          "spring.datasource.url=jdbc:h2:mem:outbox_auto_test;DB_CLOSE_DELAY=-1",
          "spring.datasource.driver-class-name=org.h2.Driver",
          "spring.sql.init.schema-locations=classpath:schema.sql");

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
    public void onEvent(EventEnvelope event) {}
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
