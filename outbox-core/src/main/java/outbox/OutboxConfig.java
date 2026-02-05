package outbox;

public final class OutboxConfig {
  private int dispatcherWorkers = 4;
  private int hotQueueCapacity = 1000;
  private int coldQueueCapacity = 1000;

  private boolean pollerEnabled = true;
  private long pollerIntervalMs = 5000L;
  private int pollerBatchSize = 200;
  private long pollerSkipRecentMs = 1000L;

  private long retryBaseDelayMs = 200L;
  private long retryMaxDelayMs = 60000L;
  private int retryMaxAttempts = 10;

  public int getDispatcherWorkers() {
    return dispatcherWorkers;
  }

  public OutboxConfig setDispatcherWorkers(int dispatcherWorkers) {
    this.dispatcherWorkers = dispatcherWorkers;
    return this;
  }

  public int getHotQueueCapacity() {
    return hotQueueCapacity;
  }

  public OutboxConfig setHotQueueCapacity(int hotQueueCapacity) {
    this.hotQueueCapacity = hotQueueCapacity;
    return this;
  }

  public int getColdQueueCapacity() {
    return coldQueueCapacity;
  }

  public OutboxConfig setColdQueueCapacity(int coldQueueCapacity) {
    this.coldQueueCapacity = coldQueueCapacity;
    return this;
  }

  public boolean isPollerEnabled() {
    return pollerEnabled;
  }

  public OutboxConfig setPollerEnabled(boolean pollerEnabled) {
    this.pollerEnabled = pollerEnabled;
    return this;
  }

  public long getPollerIntervalMs() {
    return pollerIntervalMs;
  }

  public OutboxConfig setPollerIntervalMs(long pollerIntervalMs) {
    this.pollerIntervalMs = pollerIntervalMs;
    return this;
  }

  public int getPollerBatchSize() {
    return pollerBatchSize;
  }

  public OutboxConfig setPollerBatchSize(int pollerBatchSize) {
    this.pollerBatchSize = pollerBatchSize;
    return this;
  }

  public long getPollerSkipRecentMs() {
    return pollerSkipRecentMs;
  }

  public OutboxConfig setPollerSkipRecentMs(long pollerSkipRecentMs) {
    this.pollerSkipRecentMs = pollerSkipRecentMs;
    return this;
  }

  public long getRetryBaseDelayMs() {
    return retryBaseDelayMs;
  }

  public OutboxConfig setRetryBaseDelayMs(long retryBaseDelayMs) {
    this.retryBaseDelayMs = retryBaseDelayMs;
    return this;
  }

  public long getRetryMaxDelayMs() {
    return retryMaxDelayMs;
  }

  public OutboxConfig setRetryMaxDelayMs(long retryMaxDelayMs) {
    this.retryMaxDelayMs = retryMaxDelayMs;
    return this;
  }

  public int getRetryMaxAttempts() {
    return retryMaxAttempts;
  }

  public OutboxConfig setRetryMaxAttempts(int retryMaxAttempts) {
    this.retryMaxAttempts = retryMaxAttempts;
    return this;
  }
}
