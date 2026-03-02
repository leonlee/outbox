package io.outbox.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the outbox framework.
 *
 * @see OutboxAutoConfiguration
 */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    /**
     * Operating mode: single-node, multi-node, ordered, or writer-only.
     */
    private Mode mode = Mode.SINGLE_NODE;

    /**
     * Database table name for outbox events.
     */
    private String tableName = "outbox_event";

    private final Dispatcher dispatcher = new Dispatcher();
    private final Retry retry = new Retry();
    private final Poller poller = new Poller();
    private final ClaimLocking claimLocking = new ClaimLocking();
    private final Purge purge = new Purge();
    private final Metrics metrics = new Metrics();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public Retry getRetry() {
        return retry;
    }

    public Poller getPoller() {
        return poller;
    }

    public ClaimLocking getClaimLocking() {
        return claimLocking;
    }

    public Purge getPurge() {
        return purge;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public enum Mode {
        SINGLE_NODE,
        MULTI_NODE,
        ORDERED,
        WRITER_ONLY
    }

    public static class Dispatcher {
        private int workerCount = 4;
        private int hotQueueCapacity = 1000;
        private int coldQueueCapacity = 1000;
        private int maxAttempts = 10;
        private long drainTimeoutMs = 5000;

        public int getWorkerCount() {
            return workerCount;
        }

        public void setWorkerCount(int workerCount) {
            this.workerCount = workerCount;
        }

        public int getHotQueueCapacity() {
            return hotQueueCapacity;
        }

        public void setHotQueueCapacity(int hotQueueCapacity) {
            this.hotQueueCapacity = hotQueueCapacity;
        }

        public int getColdQueueCapacity() {
            return coldQueueCapacity;
        }

        public void setColdQueueCapacity(int coldQueueCapacity) {
            this.coldQueueCapacity = coldQueueCapacity;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getDrainTimeoutMs() {
            return drainTimeoutMs;
        }

        public void setDrainTimeoutMs(long drainTimeoutMs) {
            this.drainTimeoutMs = drainTimeoutMs;
        }
    }

    public static class Retry {
        private long baseDelayMs = 200;
        private long maxDelayMs = 60000;

        public long getBaseDelayMs() {
            return baseDelayMs;
        }

        public void setBaseDelayMs(long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
        }
    }

    public static class Poller {
        private long intervalMs = 5000;
        private int batchSize = 50;
        private long skipRecentMs = 0;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getSkipRecentMs() {
            return skipRecentMs;
        }

        public void setSkipRecentMs(long skipRecentMs) {
            this.skipRecentMs = skipRecentMs;
        }
    }

    public static class ClaimLocking {
        private boolean enabled = false;
        private String ownerId = "";
        private Duration lockTimeout = Duration.ofMinutes(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(String ownerId) {
            this.ownerId = ownerId;
        }

        public Duration getLockTimeout() {
            return lockTimeout;
        }

        public void setLockTimeout(Duration lockTimeout) {
            this.lockTimeout = lockTimeout;
        }
    }

    public static class Purge {
        private boolean enabled = false;
        private Duration retention = Duration.ofDays(7);
        private int batchSize = 500;
        private long intervalSeconds = 3600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(long intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }
    }

    public static class Metrics {
        private boolean enabled = true;
        private String namePrefix = "outbox";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNamePrefix() {
            return namePrefix;
        }

        public void setNamePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
        }
    }
}
