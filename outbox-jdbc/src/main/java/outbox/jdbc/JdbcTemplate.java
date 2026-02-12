package outbox.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight JDBC helper to reduce boilerplate in event store implementations.
 */
public final class JdbcTemplate {

  @FunctionalInterface
  public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  /** Execute UPDATE, return rows affected. */
  public static int update(Connection conn, String sql, Object... params) {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bindParams(ps, params);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OutboxStoreException("Failed to execute update", e);
    }
  }

  /** Execute SELECT, map rows. */
  public static <T> List<T> query(Connection conn, String sql, RowMapper<T> mapper, Object... params) {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bindParams(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> results = new ArrayList<>();
        while (rs.next()) {
          results.add(mapper.map(rs));
        }
        return results;
      }
    } catch (SQLException e) {
      throw new OutboxStoreException("Failed to execute query", e);
    }
  }

  /** Execute UPDATE ... RETURNING, map returned rows (PostgreSQL). */
  public static <T> List<T> updateReturning(Connection conn, String sql, RowMapper<T> mapper, Object... params) {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bindParams(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> results = new ArrayList<>();
        while (rs.next()) {
          results.add(mapper.map(rs));
        }
        return results;
      }
    } catch (SQLException e) {
      throw new OutboxStoreException("Failed to execute updateReturning", e);
    }
  }

  private static void bindParams(PreparedStatement ps, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      Object param = params[i];
      if (param == null) {
        ps.setObject(i + 1, null);
      } else if (param instanceof String s) {
        ps.setString(i + 1, s);
      } else if (param instanceof Integer n) {
        ps.setInt(i + 1, n);
      } else if (param instanceof Timestamp ts) {
        ps.setTimestamp(i + 1, ts);
      } else {
        ps.setObject(i + 1, param);
      }
    }
  }

  private JdbcTemplate() {}
}
