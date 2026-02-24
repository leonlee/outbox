package outbox.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import outbox.micrometer.MicrometerMetricsExporter;
import outbox.spi.MetricsExporter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxMicrometerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxMicrometerAutoConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class);

    @Test
    void createsMicrometerExporterByDefault() {
        runner.run(ctx -> {
            assertTrue(ctx.containsBean("micrometerMetricsExporter"));
            assertInstanceOf(MicrometerMetricsExporter.class, ctx.getBean(MetricsExporter.class));
        });
    }

    @Test
    void respectsCustomNamePrefix() {
        runner.withPropertyValues("outbox.metrics.name-prefix=my.outbox").run(ctx -> {
            var exporter = ctx.getBean(MicrometerMetricsExporter.class);
            assertNotNull(exporter);
            // Verify it registered meters with the custom prefix
            var registry = ctx.getBean(MeterRegistry.class);
            assertNotNull(registry.find("my.outbox.enqueue.hot").counter());
        });
    }

    @Test
    void disabledWhenPropertyFalse() {
        runner.withPropertyValues("outbox.metrics.enabled=false").run(ctx -> {
            assertFalse(ctx.containsBean("micrometerMetricsExporter"));
        });
    }

    @Test
    void backsOffWhenCustomMetricsExporterPresent() {
        runner.withUserConfiguration(CustomExporterConfig.class).run(ctx -> {
            var exporter = ctx.getBean(MetricsExporter.class);
            assertFalse(exporter instanceof MicrometerMetricsExporter);
        });
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class CustomExporterConfig {
        @Bean
        MetricsExporter customMetricsExporter() {
            return MetricsExporter.NOOP;
        }
    }
}
