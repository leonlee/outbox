package outbox.spring.boot;

import io.micrometer.core.instrument.MeterRegistry;
import outbox.micrometer.MicrometerMetricsExporter;
import outbox.spi.MetricsExporter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Micrometer metrics integration.
 *
 * <p>Creates a {@link MicrometerMetricsExporter} when Micrometer is on the classpath
 * and {@code outbox.metrics.enabled} is true (default).
 *
 * <p>Runs before {@link OutboxAutoConfiguration} so the {@link MetricsExporter}
 * bean is available for injection into the Outbox composite.
 */
@AutoConfiguration(before = OutboxAutoConfiguration.class)
@ConditionalOnClass({MicrometerMetricsExporter.class, MeterRegistry.class})
@ConditionalOnProperty(prefix = "outbox.metrics", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxMicrometerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(MetricsExporter.class)
  public MicrometerMetricsExporter micrometerMetricsExporter(
      MeterRegistry meterRegistry, OutboxProperties props) {
    return new MicrometerMetricsExporter(meterRegistry, props.getMetrics().getNamePrefix());
  }
}
