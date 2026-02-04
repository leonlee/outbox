package outbox.core.api;

public enum OutboxStatus {
  NEW(0),
  DONE(1),
  RETRY(2),
  DEAD(3);

  private final int code;

  OutboxStatus(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }
}
