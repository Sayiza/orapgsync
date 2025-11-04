package me.christianrobert.orapgsync.transformer.inline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InlineTypeDefinition data model.
 * Tests construction, getters, helper methods, and edge cases.
 */
class InlineTypeDefinitionTest {

    @Test
    void constructRecordType() {
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("min_sal", "NUMBER", "numeric"),
                new FieldDefinition("max_sal", "NUMBER", "numeric")
        );

        InlineTypeDefinition type = new InlineTypeDefinition(
                "salary_range_t",
                TypeCategory.RECORD,
                null,  // No element type for RECORD
                fields,
                ConversionStrategy.JSONB,
                null   // No size limit
        );

        assertEquals("salary_range_t", type.getTypeName());
        assertEquals(TypeCategory.RECORD, type.getCategory());
        assertNull(type.getElementType());
        assertEquals(2, type.getFields().size());
        assertEquals(ConversionStrategy.JSONB, type.getStrategy());
        assertNull(type.getSizeLimit());
        assertNull(type.getIndexKeyType());
    }

    @Test
    void constructTableOfType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "num_list_t",
                TypeCategory.TABLE_OF,
                "NUMBER",  // Element type
                null,      // No fields
                ConversionStrategy.JSONB,
                null       // No size limit
        );

        assertEquals("num_list_t", type.getTypeName());
        assertEquals(TypeCategory.TABLE_OF, type.getCategory());
        assertEquals("NUMBER", type.getElementType());
        assertNull(type.getFields());
        assertEquals(ConversionStrategy.JSONB, type.getStrategy());
    }

    @Test
    void constructVarrayType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "codes_t",
                TypeCategory.VARRAY,
                "VARCHAR2",  // Element type
                null,        // No fields
                ConversionStrategy.JSONB,
                10           // Size limit
        );

        assertEquals("codes_t", type.getTypeName());
        assertEquals(TypeCategory.VARRAY, type.getCategory());
        assertEquals("VARCHAR2", type.getElementType());
        assertEquals(10, type.getSizeLimit());
    }

    @Test
    void constructIndexByType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "dept_map_t",
                TypeCategory.INDEX_BY,
                "VARCHAR2",  // Value type
                null,        // No fields
                ConversionStrategy.JSONB,
                null,        // No size limit
                "VARCHAR2"   // Index key type
        );

        assertEquals("dept_map_t", type.getTypeName());
        assertEquals(TypeCategory.INDEX_BY, type.getCategory());
        assertEquals("VARCHAR2", type.getElementType());
        assertEquals("VARCHAR2", type.getIndexKeyType());
    }

    @Test
    void getPostgresType_jsonbStrategy() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("field1", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        assertEquals("jsonb", type.getPostgresType());
    }

    @Test
    void getInitializer_recordType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("field1", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        assertEquals("'{}'::jsonb", type.getInitializer());
    }

    @Test
    void getInitializer_tableOfType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "num_list_t",
                TypeCategory.TABLE_OF,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null
        );

        assertEquals("'[]'::jsonb", type.getInitializer());
    }

    @Test
    void getInitializer_varrayType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "codes_t",
                TypeCategory.VARRAY,
                "VARCHAR2",
                null,
                ConversionStrategy.JSONB,
                10
        );

        assertEquals("'[]'::jsonb", type.getInitializer());
    }

    @Test
    void getInitializer_indexByType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "map_t",
                TypeCategory.INDEX_BY,
                "VARCHAR2",
                null,
                ConversionStrategy.JSONB,
                null,
                "VARCHAR2"
        );

        assertEquals("'{}'::jsonb", type.getInitializer());
    }

    @Test
    void isCollection_tableOf() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.TABLE_OF,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null
        );

        assertTrue(type.isCollection());
        assertFalse(type.isRecord());
        assertTrue(type.isIndexedCollection());
        assertFalse(type.isAssociativeArray());
    }

    @Test
    void isCollection_varray() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.VARRAY,
                "VARCHAR2",
                null,
                ConversionStrategy.JSONB,
                10
        );

        assertTrue(type.isCollection());
        assertFalse(type.isRecord());
        assertTrue(type.isIndexedCollection());
        assertFalse(type.isAssociativeArray());
    }

    @Test
    void isCollection_indexBy() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.INDEX_BY,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null,
                "VARCHAR2"
        );

        assertTrue(type.isCollection());
        assertFalse(type.isRecord());
        assertFalse(type.isIndexedCollection());
        assertTrue(type.isAssociativeArray());
    }

    @Test
    void isRecord_record() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("field1", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        assertFalse(type.isCollection());
        assertTrue(type.isRecord());
        assertFalse(type.isIndexedCollection());
        assertFalse(type.isAssociativeArray());
    }

    @Test
    void isRecord_rowtype() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.ROWTYPE,
                null,
                List.of(new FieldDefinition("empno", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        assertFalse(type.isCollection());
        assertTrue(type.isRecord());
    }

    @Test
    void fieldsAreImmutable() {
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("field1", "NUMBER", "numeric")
        );

        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                fields,
                ConversionStrategy.JSONB,
                null
        );

        // Get fields and try to modify - should throw exception
        assertThrows(UnsupportedOperationException.class, () -> {
            type.getFields().add(new FieldDefinition("field2", "VARCHAR2", "text"));
        });
    }

    @Test
    void constructorValidation_nullTypeName() {
        assertThrows(IllegalArgumentException.class, () -> {
            new InlineTypeDefinition(
                    null,
                    TypeCategory.RECORD,
                    null,
                    null,
                    ConversionStrategy.JSONB,
                    null
            );
        });
    }

    @Test
    void constructorValidation_emptyTypeName() {
        assertThrows(IllegalArgumentException.class, () -> {
            new InlineTypeDefinition(
                    "  ",
                    TypeCategory.RECORD,
                    null,
                    null,
                    ConversionStrategy.JSONB,
                    null
            );
        });
    }

    @Test
    void constructorValidation_nullCategory() {
        assertThrows(IllegalArgumentException.class, () -> {
            new InlineTypeDefinition(
                    "test_t",
                    null,
                    null,
                    null,
                    ConversionStrategy.JSONB,
                    null
            );
        });
    }

    @Test
    void constructorValidation_nullStrategy() {
        assertThrows(IllegalArgumentException.class, () -> {
            new InlineTypeDefinition(
                    "test_t",
                    TypeCategory.RECORD,
                    null,
                    null,
                    null,
                    null
            );
        });
    }

    @Test
    void equals_sameContent() {
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("field1", "NUMBER", "numeric")
        );

        InlineTypeDefinition type1 = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                fields,
                ConversionStrategy.JSONB,
                null
        );

        InlineTypeDefinition type2 = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                fields,
                ConversionStrategy.JSONB,
                null
        );

        assertEquals(type1, type2);
        assertEquals(type1.hashCode(), type2.hashCode());
    }

    @Test
    void equals_differentTypeName() {
        InlineTypeDefinition type1 = new InlineTypeDefinition(
                "type1_t",
                TypeCategory.RECORD,
                null,
                null,
                ConversionStrategy.JSONB,
                null
        );

        InlineTypeDefinition type2 = new InlineTypeDefinition(
                "type2_t",
                TypeCategory.RECORD,
                null,
                null,
                ConversionStrategy.JSONB,
                null
        );

        assertNotEquals(type1, type2);
    }

    @Test
    void toString_containsKeyInfo() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.TABLE_OF,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null
        );

        String str = type.toString();
        assertTrue(str.contains("test_t"));
        assertTrue(str.contains("TABLE_OF"));
        assertTrue(str.contains("NUMBER"));
        assertTrue(str.contains("JSONB"));
    }
}
