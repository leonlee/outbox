package outbox.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TableNamesTest {

    @Test
    void validTableNameReturnsName() {
        assertEquals("outbox_event", TableNames.validate("outbox_event"));
        assertEquals("MyTable", TableNames.validate("MyTable"));
        assertEquals("events123", TableNames.validate("events123"));
    }

    @Test
    void defaultTableConstant() {
        assertEquals("outbox_event", TableNames.DEFAULT_TABLE);
    }

    @Test
    void nullTableNameThrows() {
        assertThrows(NullPointerException.class, () ->
                TableNames.validate(null));
    }

    @Test
    void emptyTableNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TableNames.validate(""));
    }

    @Test
    void tableNameStartingWithDigitThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TableNames.validate("1table"));
    }

    @Test
    void tableNameWithSpecialCharsThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TableNames.validate("my-table"));
        assertThrows(IllegalArgumentException.class, () ->
                TableNames.validate("my.table"));
    }

    @Test
    void underscorePrefixIsValid() {
        assertEquals("_table", TableNames.validate("_table"));
    }
}
