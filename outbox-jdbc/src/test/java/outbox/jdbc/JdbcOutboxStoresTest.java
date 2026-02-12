package outbox.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import outbox.jdbc.store.AbstractJdbcOutboxStore;
import outbox.jdbc.store.JdbcOutboxStores;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JdbcOutboxStoresTest {

  @Test
  void allReturnsBuiltInOutboxStores() {
    List<AbstractJdbcOutboxStore> stores = JdbcOutboxStores.all();

    assertTrue(stores.size() >= 3);
    assertTrue(stores.stream().anyMatch(s -> s.name().equals("mysql")));
    assertTrue(stores.stream().anyMatch(s -> s.name().equals("postgresql")));
    assertTrue(stores.stream().anyMatch(s -> s.name().equals("h2")));
  }

  @Test
  void getByNameReturnsOutboxStore() {
    AbstractJdbcOutboxStore mysql = JdbcOutboxStores.get("mysql");
    assertEquals("mysql", mysql.name());

    AbstractJdbcOutboxStore postgresql = JdbcOutboxStores.get("postgresql");
    assertEquals("postgresql", postgresql.name());

    AbstractJdbcOutboxStore h2 = JdbcOutboxStores.get("h2");
    assertEquals("h2", h2.name());
  }

  @Test
  void getByNameIsCaseInsensitive() {
    assertEquals("mysql", JdbcOutboxStores.get("MySQL").name());
    assertEquals("postgresql", JdbcOutboxStores.get("POSTGRESQL").name());
    assertEquals("h2", JdbcOutboxStores.get("H2").name());
  }

  @Test
  void getByNameThrowsForUnknown() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcOutboxStores.get("oracle"));
    assertTrue(ex.getMessage().contains("Unknown outbox store"));
    assertTrue(ex.getMessage().contains("oracle"));
  }

  @Test
  void detectFromJdbcUrlMySql() {
    AbstractJdbcOutboxStore store = JdbcOutboxStores.detect("jdbc:mysql://localhost:3306/mydb");
    assertEquals("mysql", store.name());
  }

  @Test
  void detectFromJdbcUrlTiDb() {
    AbstractJdbcOutboxStore store = JdbcOutboxStores.detect("jdbc:tidb://localhost:4000/mydb");
    assertEquals("mysql", store.name());
  }

  @Test
  void detectFromJdbcUrlPostgres() {
    AbstractJdbcOutboxStore store = JdbcOutboxStores.detect("jdbc:postgresql://localhost:5432/mydb");
    assertEquals("postgresql", store.name());
  }

  @Test
  void detectFromJdbcUrlH2() {
    AbstractJdbcOutboxStore store = JdbcOutboxStores.detect("jdbc:h2:mem:test");
    assertEquals("h2", store.name());
  }

  @Test
  void detectFromJdbcUrlThrowsForUnknown() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> JdbcOutboxStores.detect("jdbc:oracle:thin:@localhost:1521:xe"));
    assertTrue(ex.getMessage().contains("No outbox store found"));
  }

  @Test
  void detectFromJdbcUrlThrowsForNull() {
    assertThrows(IllegalArgumentException.class, () -> JdbcOutboxStores.detect((String) null));
  }

  @Test
  void detectFromJdbcUrlThrowsForEmpty() {
    assertThrows(IllegalArgumentException.class, () -> JdbcOutboxStores.detect(""));
  }

  @Test
  void detectFromDataSource() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:event_store_test;DB_CLOSE_DELAY=-1");

    AbstractJdbcOutboxStore store = JdbcOutboxStores.detect(ds);
    assertEquals("h2", store.name());
  }

  @Test
  void mySqlOutboxStoreHandlesTiDbPrefix() {
    AbstractJdbcOutboxStore mysql = JdbcOutboxStores.get("mysql");
    assertTrue(mysql.jdbcUrlPrefixes().contains("jdbc:mysql:"));
    assertTrue(mysql.jdbcUrlPrefixes().contains("jdbc:tidb:"));
  }
}
