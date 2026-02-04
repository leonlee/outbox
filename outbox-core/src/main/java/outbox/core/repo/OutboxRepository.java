package outbox.core.repo;

import outbox.core.api.EventEnvelope;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface OutboxRepository {
  void insertNew(Connection conn, EventEnvelope event);

  int markDone(Connection conn, String eventId);

  int markRetry(Connection conn, String eventId, Instant nextAt, String error);

  int markDead(Connection conn, String eventId, String error);

  List<OutboxRow> pollPending(Connection conn, Instant now, Duration skipRecent, int limit);
}
