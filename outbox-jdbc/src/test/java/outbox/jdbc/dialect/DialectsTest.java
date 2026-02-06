package outbox.jdbc.dialect;

import outbox.jdbc.spi.Dialect;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DialectsTest {

  @Test
  void allReturnsBuiltInDialects() {
    List<Dialect> dialects = Dialects.all();

    assertTrue(dialects.size() >= 3);
    assertTrue(dialects.stream().anyMatch(d -> d.name().equals("mysql")));
    assertTrue(dialects.stream().anyMatch(d -> d.name().equals("postgresql")));
    assertTrue(dialects.stream().anyMatch(d -> d.name().equals("h2")));
  }

  @Test
  void getByNameReturnsDialect() {
    Dialect mysql = Dialects.get("mysql");
    assertEquals("mysql", mysql.name());

    Dialect postgresql = Dialects.get("postgresql");
    assertEquals("postgresql", postgresql.name());

    Dialect h2 = Dialects.get("h2");
    assertEquals("h2", h2.name());
  }

  @Test
  void getByNameIsCaseInsensitive() {
    assertEquals("mysql", Dialects.get("MySQL").name());
    assertEquals("postgresql", Dialects.get("POSTGRESQL").name());
    assertEquals("h2", Dialects.get("H2").name());
  }

  @Test
  void getByNameThrowsForUnknown() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> Dialects.get("oracle"));
    assertTrue(ex.getMessage().contains("Unknown dialect"));
    assertTrue(ex.getMessage().contains("oracle"));
  }

  @Test
  void detectFromJdbcUrlMySql() {
    Dialect dialect = Dialects.detect("jdbc:mysql://localhost:3306/mydb");
    assertEquals("mysql", dialect.name());
  }

  @Test
  void detectFromJdbcUrlTiDb() {
    Dialect dialect = Dialects.detect("jdbc:tidb://localhost:4000/mydb");
    assertEquals("mysql", dialect.name());
  }

  @Test
  void detectFromJdbcUrlPostgres() {
    Dialect dialect = Dialects.detect("jdbc:postgresql://localhost:5432/mydb");
    assertEquals("postgresql", dialect.name());
  }

  @Test
  void detectFromJdbcUrlH2() {
    Dialect dialect = Dialects.detect("jdbc:h2:mem:test");
    assertEquals("h2", dialect.name());
  }

  @Test
  void detectFromJdbcUrlThrowsForUnknown() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> Dialects.detect("jdbc:oracle:thin:@localhost:1521:xe"));
    assertTrue(ex.getMessage().contains("No dialect found"));
  }

  @Test
  void detectFromJdbcUrlThrowsForNull() {
    assertThrows(IllegalArgumentException.class, () -> Dialects.detect((String) null));
  }

  @Test
  void detectFromJdbcUrlThrowsForEmpty() {
    assertThrows(IllegalArgumentException.class, () -> Dialects.detect(""));
  }

  @Test
  void detectFromDataSource() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:dialect_test;DB_CLOSE_DELAY=-1");

    Dialect dialect = Dialects.detect(ds);
    assertEquals("h2", dialect.name());
  }

  @Test
  void mySqlDialectHandlesTiDbPrefix() {
    Dialect mysql = Dialects.get("mysql");
    assertTrue(mysql.jdbcUrlPrefixes().contains("jdbc:mysql:"));
    assertTrue(mysql.jdbcUrlPrefixes().contains("jdbc:tidb:"));
  }

  @Test
  void dialectGeneratesValidSql() {
    Dialect dialect = Dialects.get("h2");
    String table = "outbox_event";

    assertNotNull(dialect.insertSql(table));
    assertTrue(dialect.insertSql(table).contains("INSERT INTO " + table));

    assertNotNull(dialect.markDoneSql(table));
    assertTrue(dialect.markDoneSql(table).contains("UPDATE " + table));

    assertNotNull(dialect.markRetrySql(table));
    assertTrue(dialect.markRetrySql(table).contains("UPDATE " + table));

    assertNotNull(dialect.markDeadSql(table));
    assertTrue(dialect.markDeadSql(table).contains("UPDATE " + table));

    assertNotNull(dialect.pollPendingSql(table));
    assertTrue(dialect.pollPendingSql(table).contains("SELECT"));
    assertTrue(dialect.pollPendingSql(table).contains("FROM " + table));
    assertTrue(dialect.pollPendingSql(table).contains("LIMIT"));
  }
}
