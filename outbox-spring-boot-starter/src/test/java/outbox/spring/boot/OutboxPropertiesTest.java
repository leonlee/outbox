package outbox.spring.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class);

    @Test
    void defaultValues() {
        runner.run(ctx -> {
            var props = ctx.getBean(OutboxProperties.class);
            assertEquals(OutboxProperties.Mode.SINGLE_NODE, props.getMode());
            assertEquals("outbox_event", props.getTableName());
            assertEquals(4, props.getDispatcher().getWorkerCount());
            assertEquals(1000, props.getDispatcher().getHotQueueCapacity());
            assertEquals(1000, props.getDispatcher().getColdQueueCapacity());
            assertEquals(10, props.getDispatcher().getMaxAttempts());
            assertEquals(5000, props.getDispatcher().getDrainTimeoutMs());
            assertEquals(200, props.getRetry().getBaseDelayMs());
            assertEquals(60000, props.getRetry().getMaxDelayMs());
            assertEquals(5000, props.getPoller().getIntervalMs());
            assertEquals(50, props.getPoller().getBatchSize());
            assertEquals(0, props.getPoller().getSkipRecentMs());
            assertFalse(props.getClaimLocking().isEnabled());
            assertEquals(Duration.ofMinutes(5), props.getClaimLocking().getLockTimeout());
            assertFalse(props.getPurge().isEnabled());
            assertEquals(Duration.ofDays(7), props.getPurge().getRetention());
            assertEquals(500, props.getPurge().getBatchSize());
            assertEquals(3600, props.getPurge().getIntervalSeconds());
            assertTrue(props.getMetrics().isEnabled());
            assertEquals("outbox", props.getMetrics().getNamePrefix());
        });
    }

    @Test
    void customValues() {
        runner.withPropertyValues(
                "outbox.mode=MULTI_NODE",
                "outbox.table-name=my_outbox",
                "outbox.dispatcher.worker-count=8",
                "outbox.dispatcher.hot-queue-capacity=2000",
                "outbox.dispatcher.cold-queue-capacity=500",
                "outbox.dispatcher.max-attempts=5",
                "outbox.dispatcher.drain-timeout-ms=10000",
                "outbox.retry.base-delay-ms=500",
                "outbox.retry.max-delay-ms=120000",
                "outbox.poller.interval-ms=1000",
                "outbox.poller.batch-size=100",
                "outbox.poller.skip-recent-ms=2000",
                "outbox.claim-locking.enabled=true",
                "outbox.claim-locking.owner-id=node-1",
                "outbox.claim-locking.lock-timeout=PT10M",
                "outbox.purge.enabled=true",
                "outbox.purge.retention=P14D",
                "outbox.purge.batch-size=1000",
                "outbox.purge.interval-seconds=7200",
                "outbox.metrics.enabled=false",
                "outbox.metrics.name-prefix=my.outbox"
        ).run(ctx -> {
            var props = ctx.getBean(OutboxProperties.class);
            assertEquals(OutboxProperties.Mode.MULTI_NODE, props.getMode());
            assertEquals("my_outbox", props.getTableName());
            assertEquals(8, props.getDispatcher().getWorkerCount());
            assertEquals(2000, props.getDispatcher().getHotQueueCapacity());
            assertEquals(500, props.getDispatcher().getColdQueueCapacity());
            assertEquals(5, props.getDispatcher().getMaxAttempts());
            assertEquals(10000, props.getDispatcher().getDrainTimeoutMs());
            assertEquals(500, props.getRetry().getBaseDelayMs());
            assertEquals(120000, props.getRetry().getMaxDelayMs());
            assertEquals(1000, props.getPoller().getIntervalMs());
            assertEquals(100, props.getPoller().getBatchSize());
            assertEquals(2000, props.getPoller().getSkipRecentMs());
            assertTrue(props.getClaimLocking().isEnabled());
            assertEquals("node-1", props.getClaimLocking().getOwnerId());
            assertEquals(Duration.ofMinutes(10), props.getClaimLocking().getLockTimeout());
            assertTrue(props.getPurge().isEnabled());
            assertEquals(Duration.ofDays(14), props.getPurge().getRetention());
            assertEquals(1000, props.getPurge().getBatchSize());
            assertEquals(7200, props.getPurge().getIntervalSeconds());
            assertFalse(props.getMetrics().isEnabled());
            assertEquals("my.outbox", props.getMetrics().getNamePrefix());
        });
    }

    @Configuration
    @EnableConfigurationProperties(OutboxProperties.class)
    static class PropsConfig {
    }
}
