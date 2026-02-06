package outbox.spi;

import outbox.EventEnvelope;
import outbox.model.OutboxEvent;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface EventStore {
  void insertNew(Connection conn, EventEnvelope event);

  int markDone(Connection conn, String eventId);

  int markRetry(Connection conn, String eventId, Instant nextAt, String error);

  int markDead(Connection conn, String eventId, String error);

  List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit);

  /**
   * Claim and return pending events with locking.
   *
   * <p>Default falls back to {@link #pollPending} (no locking).
   */
  default List<OutboxEvent> claimPending(
      Connection conn, String ownerId, Instant now,
      Instant lockExpiry, Duration skipRecent, int limit) {
    return pollPending(conn, now, skipRecent, limit);
  }
}
