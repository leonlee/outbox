package outbox.dead;

import outbox.model.OutboxEvent;
import outbox.spi.ConnectionProvider;
import outbox.spi.OutboxStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Convenience facade for querying, counting, and replaying DEAD events.
 *
 * <p>Manages connection lifecycle internally using a {@link ConnectionProvider},
 * similar to how {@link outbox.purge.OutboxPurgeScheduler} wraps {@link outbox.spi.EventPurger}.
 *
 * @see OutboxStore#queryDead
 * @see OutboxStore#replayDead
 * @see OutboxStore#countDead
 */
public final class DeadEventManager {
  private static final Logger logger = Logger.getLogger(DeadEventManager.class.getName());

  private final ConnectionProvider connectionProvider;
  private final OutboxStore outboxStore;

  public DeadEventManager(ConnectionProvider connectionProvider, OutboxStore outboxStore) {
    this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
  }

  /**
   * Queries DEAD events with optional filters.
   *
   * @param eventType     optional event type filter ({@code null} for all)
   * @param aggregateType optional aggregate type filter ({@code null} for all)
   * @param limit         maximum number of events to return
   * @return list of dead events, oldest first
   */
  public List<OutboxEvent> query(String eventType, String aggregateType, int limit) {
    try (Connection conn = connectionProvider.getConnection()) {
      return outboxStore.queryDead(conn, eventType, aggregateType, limit);
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to query dead events", e);
      return List.of();
    }
  }

  /**
   * Replays a single DEAD event by resetting it to NEW status.
   *
   * @param eventId the event ID to replay
   * @return {@code true} if the event was replayed, {@code false} if not found or not DEAD
   */
  public boolean replay(String eventId) {
    try (Connection conn = connectionProvider.getConnection()) {
      return outboxStore.replayDead(conn, eventId) > 0;
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to replay dead event: " + eventId, e);
      return false;
    }
  }

  /**
   * Replays all DEAD events matching the given filters, processing in batches.
   *
   * @param eventType     optional event type filter ({@code null} for all)
   * @param aggregateType optional aggregate type filter ({@code null} for all)
   * @param batchSize     number of events to process per batch
   * @return total number of events replayed
   */
  public int replayAll(String eventType, String aggregateType, int batchSize) {
    int totalReplayed = 0;
    List<OutboxEvent> batch;
    do {
      int batchReplayed = 0;
      try (Connection conn = connectionProvider.getConnection()) {
        batch = outboxStore.queryDead(conn, eventType, aggregateType, batchSize);
        for (OutboxEvent event : batch) {
          if (outboxStore.replayDead(conn, event.eventId()) > 0) {
            batchReplayed++;
          }
        }
      } catch (SQLException e) {
        logger.log(Level.SEVERE, "Failed to replay dead events batch", e);
        break;
      }
      totalReplayed += batchReplayed;
    } while (batch.size() >= batchSize);
    return totalReplayed;
  }

  /**
   * Counts DEAD events, optionally filtered by event type.
   *
   * @param eventType optional event type filter ({@code null} for all)
   * @return the number of dead events matching the filter
   */
  public int count(String eventType) {
    try (Connection conn = connectionProvider.getConnection()) {
      return outboxStore.countDead(conn, eventType);
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Failed to count dead events", e);
      return 0;
    }
  }
}
