package me.christianrobert.orapgsync.transformer.inline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FieldDefinition data model.
 */
class FieldDefinitionTest {

    @Test
    void construct_validParameters() {
        FieldDefinition field = new FieldDefinition("min_sal", "NUMBER", "numeric");

        assertEquals("min_sal", field.getFieldName());
        assertEquals("NUMBER", field.getOracleType());
        assertEquals("numeric", field.getPostgresType());
    }

    @Test
    void construct_nullFieldName() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FieldDefinition(null, "NUMBER", "numeric");
        });
    }

    @Test
    void construct_emptyFieldName() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FieldDefinition("  ", "NUMBER", "numeric");
        });
    }

    @Test
    void construct_nullOracleType() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FieldDefinition("field1", null, "numeric");
        });
    }

    @Test
    void construct_emptyOracleType() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FieldDefinition("field1", "  ", "numeric");
        });
    }

    @Test
    void construct_nullPostgresType() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FieldDefinition("field1", "NUMBER", null);
        });
    }

    @Test
    void construct_emptyPostgresType() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FieldDefinition("field1", "NUMBER", "  ");
        });
    }

    @Test
    void equals_sameContent() {
        FieldDefinition field1 = new FieldDefinition("min_sal", "NUMBER", "numeric");
        FieldDefinition field2 = new FieldDefinition("min_sal", "NUMBER", "numeric");

        assertEquals(field1, field2);
        assertEquals(field1.hashCode(), field2.hashCode());
    }

    @Test
    void equals_differentFieldName() {
        FieldDefinition field1 = new FieldDefinition("min_sal", "NUMBER", "numeric");
        FieldDefinition field2 = new FieldDefinition("max_sal", "NUMBER", "numeric");

        assertNotEquals(field1, field2);
    }

    @Test
    void equals_differentOracleType() {
        FieldDefinition field1 = new FieldDefinition("field1", "NUMBER", "numeric");
        FieldDefinition field2 = new FieldDefinition("field1", "VARCHAR2", "numeric");

        assertNotEquals(field1, field2);
    }

    @Test
    void equals_differentPostgresType() {
        FieldDefinition field1 = new FieldDefinition("field1", "NUMBER", "numeric");
        FieldDefinition field2 = new FieldDefinition("field1", "NUMBER", "integer");

        assertNotEquals(field1, field2);
    }

    @Test
    void toString_containsAllFields() {
        FieldDefinition field = new FieldDefinition("min_sal", "NUMBER", "numeric");
        String str = field.toString();

        assertTrue(str.contains("min_sal"));
        assertTrue(str.contains("NUMBER"));
        assertTrue(str.contains("numeric"));
    }

    @Test
    void construct_varchar2Type() {
        FieldDefinition field = new FieldDefinition("name", "VARCHAR2(100)", "text");

        assertEquals("name", field.getFieldName());
        assertEquals("VARCHAR2(100)", field.getOracleType());
        assertEquals("text", field.getPostgresType());
    }

    @Test
    void construct_dateType() {
        FieldDefinition field = new FieldDefinition("created_date", "DATE", "timestamp");

        assertEquals("created_date", field.getFieldName());
        assertEquals("DATE", field.getOracleType());
        assertEquals("timestamp", field.getPostgresType());
    }
}
