package outbox.jdbc;

import outbox.core.api.EventEnvelope;
import outbox.core.util.JsonCodec;
import outbox.core.repo.OutboxRepository;
import outbox.core.repo.OutboxRow;
import outbox.core.api.OutboxStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class JdbcOutboxRepository implements OutboxRepository {
  @Override
  public void insertNew(Connection conn, EventEnvelope event) {
    String sql = "INSERT INTO outbox_event (" +
        "event_id, event_type, aggregate_type, aggregate_id, tenant_id, " +
        "payload, headers, status, attempts, available_at, created_at, done_at, last_error" +
        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,NULL)";

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, event.eventId());
      ps.setString(2, event.eventType());
      ps.setString(3, event.aggregateType());
      ps.setString(4, event.aggregateId());
      ps.setString(5, event.tenantId());
      ps.setString(6, event.payloadJson());
      ps.setString(7, JsonCodec.toJson(event.headers()));
      ps.setInt(8, OutboxStatus.NEW.code());
      ps.setInt(9, 0);
      Timestamp now = Timestamp.from(event.occurredAt());
      ps.setTimestamp(10, now);
      ps.setTimestamp(11, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to insert outbox row", e);
    }
  }

  @Override
  public int markDone(Connection conn, String eventId) {
    String sql = "UPDATE outbox_event SET status=1, done_at=? WHERE event_id=? AND status<>1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(Instant.now()));
      ps.setString(2, eventId);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to mark DONE", e);
    }
  }

  @Override
  public int markRetry(Connection conn, String eventId, Instant nextAt, String error) {
    String sql = "UPDATE outbox_event SET status=2, attempts=attempts+1, available_at=?, last_error=? " +
        "WHERE event_id=? AND status<>1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(nextAt));
      ps.setString(2, error);
      ps.setString(3, eventId);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to mark RETRY", e);
    }
  }

  @Override
  public int markDead(Connection conn, String eventId, String error) {
    String sql = "UPDATE outbox_event SET status=3, last_error=? WHERE event_id=? AND status<>1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, error);
      ps.setString(2, eventId);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to mark DEAD", e);
    }
  }

  @Override
  public List<OutboxRow> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
    String sql = "SELECT event_id, event_type, aggregate_type, aggregate_id, tenant_id, payload, headers, attempts, created_at " +
        "FROM outbox_event WHERE status IN (0,2) AND available_at <= ? AND created_at <= ? " +
        "ORDER BY created_at LIMIT ?";
    Instant recentCutoff = skipRecent == null ? now : now.minus(skipRecent);
    List<OutboxRow> results = new ArrayList<>();

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(now));
      ps.setTimestamp(2, Timestamp.from(recentCutoff));
      ps.setInt(3, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          results.add(new OutboxRow(
              rs.getString("event_id"),
              rs.getString("event_type"),
              rs.getString("aggregate_type"),
              rs.getString("aggregate_id"),
              rs.getString("tenant_id"),
              rs.getString("payload"),
              rs.getString("headers"),
              rs.getInt("attempts"),
              rs.getTimestamp("created_at").toInstant()
          ));
        }
      }
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to poll pending outbox rows", e);
    }

    return results;
  }
}
