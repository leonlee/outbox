package outbox.model;

public enum EventStatus {
  NEW(0),
  DONE(1),
  RETRY(2),
  DEAD(3);

  private final int code;

  EventStatus(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }
}
