package outbox.jdbc;

import outbox.EventEnvelope;
import outbox.model.EventStatus;
import outbox.model.OutboxEvent;
import outbox.spi.EventStore;
import outbox.util.JsonCodec;
import outbox.jdbc.spi.Dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcOutboxRepository implements EventStore {
  private static final int MAX_ERROR_LENGTH = 4000;
  private static final String DEFAULT_TABLE = "outbox_event";

  private final Dialect dialect;
  private final String tableName;

  public JdbcOutboxRepository(Dialect dialect) {
    this(dialect, DEFAULT_TABLE);
  }

  public JdbcOutboxRepository(Dialect dialect, String tableName) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.tableName = Objects.requireNonNull(tableName, "tableName");
  }

  private static String truncateError(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH - 3) + "...";
  }

  @Override
  public void insertNew(Connection conn, EventEnvelope event) {
    String sql = dialect.insertSql(tableName);

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, event.eventId());
      ps.setString(2, event.eventType());
      ps.setString(3, event.aggregateType());
      ps.setString(4, event.aggregateId());
      ps.setString(5, event.tenantId());
      ps.setString(6, event.payloadJson());
      ps.setString(7, JsonCodec.toJson(event.headers()));
      ps.setInt(8, EventStatus.NEW.code());
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
    String sql = dialect.markDoneSql(tableName);
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
    String sql = dialect.markRetrySql(tableName);
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(nextAt));
      ps.setString(2, truncateError(error));
      ps.setString(3, eventId);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to mark RETRY", e);
    }
  }

  @Override
  public int markDead(Connection conn, String eventId, String error) {
    String sql = dialect.markDeadSql(tableName);
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, truncateError(error));
      ps.setString(2, eventId);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxRepositoryException("Failed to mark DEAD", e);
    }
  }

  @Override
  public List<OutboxEvent> pollPending(Connection conn, Instant now, Duration skipRecent, int limit) {
    String sql = dialect.pollPendingSql(tableName);
    Instant recentCutoff = skipRecent == null ? now : now.minus(skipRecent);
    List<OutboxEvent> results = new ArrayList<>();

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(now));
      ps.setTimestamp(2, Timestamp.from(recentCutoff));
      ps.setInt(3, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          results.add(new OutboxEvent(
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
