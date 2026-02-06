package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcEventStoresTest {

  @Test
  void allReturnsBuiltInEventStores() {
    List<AbstractJdbcEventStore> stores = JdbcEventStores.all();

    assertTrue(stores.size() >= 3);
    assertTrue(stores.stream().anyMatch(s -> s.name().equals("mysql")));
    assertTrue(stores.stream().anyMatch(s -> s.name().equals("postgresql")));
    assertTrue(stores.stream().anyMatch(s -> s.name().equals("h2")));
  }

  @Test
  void getByNameReturnsEventStore() {
    AbstractJdbcEventStore mysql = JdbcEventStores.get("mysql");
    assertEquals("mysql", mysql.name());

    AbstractJdbcEventStore postgresql = JdbcEventStores.get("postgresql");
    assertEquals("postgresql", postgresql.name());

    AbstractJdbcEventStore h2 = JdbcEventStores.get("h2");
    assertEquals("h2", h2.name());
  }

  @Test
  void getByNameIsCaseInsensitive() {
    assertEquals("mysql", JdbcEventStores.get("MySQL").name());
    assertEquals("postgresql", JdbcEventStores.get("POSTGRESQL").name());
    assertEquals("h2", JdbcEventStores.get("H2").name());
  }

  @Test
  void getByNameThrowsForUnknown() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcEventStores.get("oracle"));
    assertTrue(ex.getMessage().contains("Unknown event store"));
    assertTrue(ex.getMessage().contains("oracle"));
  }

  @Test
  void detectFromJdbcUrlMySql() {
    AbstractJdbcEventStore store = JdbcEventStores.detect("jdbc:mysql://localhost:3306/mydb");
    assertEquals("mysql", store.name());
  }

  @Test
  void detectFromJdbcUrlTiDb() {
    AbstractJdbcEventStore store = JdbcEventStores.detect("jdbc:tidb://localhost:4000/mydb");
    assertEquals("mysql", store.name());
  }

  @Test
  void detectFromJdbcUrlPostgres() {
    AbstractJdbcEventStore store = JdbcEventStores.detect("jdbc:postgresql://localhost:5432/mydb");
    assertEquals("postgresql", store.name());
  }

  @Test
  void detectFromJdbcUrlH2() {
    AbstractJdbcEventStore store = JdbcEventStores.detect("jdbc:h2:mem:test");
    assertEquals("h2", store.name());
  }

  @Test
  void detectFromJdbcUrlThrowsForUnknown() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcEventStores.detect("jdbc:oracle:thin:@localhost:1521:xe"));
    assertTrue(ex.getMessage().contains("No event store found"));
  }

  @Test
  void detectFromJdbcUrlThrowsForNull() {
    assertThrows(IllegalArgumentException.class, () -> JdbcEventStores.detect((String) null));
  }

  @Test
  void detectFromJdbcUrlThrowsForEmpty() {
    assertThrows(IllegalArgumentException.class, () -> JdbcEventStores.detect(""));
  }

  @Test
  void detectFromDataSource() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:event_store_test;DB_CLOSE_DELAY=-1");

    AbstractJdbcEventStore store = JdbcEventStores.detect(ds);
    assertEquals("h2", store.name());
  }

  @Test
  void mySqlEventStoreHandlesTiDbPrefix() {
    AbstractJdbcEventStore mysql = JdbcEventStores.get("mysql");
    assertTrue(mysql.jdbcUrlPrefixes().contains("jdbc:mysql:"));
    assertTrue(mysql.jdbcUrlPrefixes().contains("jdbc:tidb:"));
  }
}
