package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransformationContext inline type support.
 * Tests registration, lookup, and case-insensitive behavior.
 */
class TransformationContextInlineTypeTest {

    private TransformationContext context;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        // Create minimal indices (empty)
        indices = new TransformationIndices(
                new java.util.HashMap<>(),  // tableColumns
                new java.util.HashMap<>(),  // typeMethods
                new java.util.HashSet<>(),  // packageFunctions
                new java.util.HashMap<>()   // synonyms
        );

        // Create context with no package context
        context = new TransformationContext(
                "hr",
                indices,
                new SimpleTypeEvaluator("hr", indices)
        );
    }

    @Test
    void registerAndGetInlineType() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "salary_range_t",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("min_sal", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        context.registerInlineType("salary_range_t", type);

        InlineTypeDefinition retrieved = context.getInlineType("salary_range_t");
        assertNotNull(retrieved);
        assertEquals("salary_range_t", retrieved.getTypeName());
        assertEquals(TypeCategory.RECORD, retrieved.getCategory());
    }

    @Test
    void getInlineType_caseInsensitive() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "SALARY_RANGE_T",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("min_sal", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        context.registerInlineType("SALARY_RANGE_T", type);

        // Should find with lowercase
        assertNotNull(context.getInlineType("salary_range_t"));
        // Should find with uppercase
        assertNotNull(context.getInlineType("SALARY_RANGE_T"));
        // Should find with mixed case
        assertNotNull(context.getInlineType("Salary_Range_T"));
    }

    @Test
    void getInlineType_notFound() {
        InlineTypeDefinition retrieved = context.getInlineType("nonexistent_type");
        assertNull(retrieved);
    }

    @Test
    void getInlineType_nullTypeName() {
        InlineTypeDefinition retrieved = context.getInlineType(null);
        assertNull(retrieved);
    }

    @Test
    void registerInlineType_nullTypeName() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                null,
                ConversionStrategy.JSONB,
                null
        );

        // Should not throw, just ignore
        context.registerInlineType(null, type);

        // Type should not be registered
        assertNull(context.getInlineType("test_t"));
    }

    @Test
    void registerInlineType_nullDefinition() {
        // Should not throw, just ignore
        context.registerInlineType("test_t", null);

        // Type should not be registered
        assertNull(context.getInlineType("test_t"));
    }

    @Test
    void registerMultipleTypes() {
        InlineTypeDefinition type1 = new InlineTypeDefinition(
                "type1_t",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("field1", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        InlineTypeDefinition type2 = new InlineTypeDefinition(
                "type2_t",
                TypeCategory.TABLE_OF,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null
        );

        InlineTypeDefinition type3 = new InlineTypeDefinition(
                "type3_t",
                TypeCategory.INDEX_BY,
                "VARCHAR2",
                null,
                ConversionStrategy.JSONB,
                null,
                "VARCHAR2"
        );

        context.registerInlineType("type1_t", type1);
        context.registerInlineType("type2_t", type2);
        context.registerInlineType("type3_t", type3);

        // All three should be retrievable
        assertNotNull(context.getInlineType("type1_t"));
        assertNotNull(context.getInlineType("type2_t"));
        assertNotNull(context.getInlineType("type3_t"));

        // Verify correct types
        assertEquals(TypeCategory.RECORD, context.getInlineType("type1_t").getCategory());
        assertEquals(TypeCategory.TABLE_OF, context.getInlineType("type2_t").getCategory());
        assertEquals(TypeCategory.INDEX_BY, context.getInlineType("type3_t").getCategory());
    }

    @Test
    void registerInlineType_overwriteExisting() {
        InlineTypeDefinition type1 = new InlineTypeDefinition(
                "test_t",
                TypeCategory.RECORD,
                null,
                List.of(new FieldDefinition("field1", "NUMBER", "numeric")),
                ConversionStrategy.JSONB,
                null
        );

        InlineTypeDefinition type2 = new InlineTypeDefinition(
                "test_t",
                TypeCategory.TABLE_OF,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null
        );

        context.registerInlineType("test_t", type1);
        context.registerInlineType("test_t", type2);

        // Should have the second type
        InlineTypeDefinition retrieved = context.getInlineType("test_t");
        assertNotNull(retrieved);
        assertEquals(TypeCategory.TABLE_OF, retrieved.getCategory());
    }

    @Test
    void tableOfType_registration() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "num_list_t",
                TypeCategory.TABLE_OF,
                "NUMBER",
                null,
                ConversionStrategy.JSONB,
                null
        );

        context.registerInlineType("num_list_t", type);

        InlineTypeDefinition retrieved = context.getInlineType("num_list_t");
        assertEquals("NUMBER", retrieved.getElementType());
        assertTrue(retrieved.isCollection());
        assertTrue(retrieved.isIndexedCollection());
    }

    @Test
    void varrayType_registration() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "codes_t",
                TypeCategory.VARRAY,
                "VARCHAR2",
                null,
                ConversionStrategy.JSONB,
                10
        );

        context.registerInlineType("codes_t", type);

        InlineTypeDefinition retrieved = context.getInlineType("codes_t");
        assertEquals(10, retrieved.getSizeLimit());
        assertTrue(retrieved.isCollection());
        assertTrue(retrieved.isIndexedCollection());
    }

    @Test
    void indexByType_registration() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "dept_map_t",
                TypeCategory.INDEX_BY,
                "VARCHAR2",
                null,
                ConversionStrategy.JSONB,
                null,
                "VARCHAR2"
        );

        context.registerInlineType("dept_map_t", type);

        InlineTypeDefinition retrieved = context.getInlineType("dept_map_t");
        assertEquals("VARCHAR2", retrieved.getIndexKeyType());
        assertTrue(retrieved.isCollection());
        assertTrue(retrieved.isAssociativeArray());
    }

    @Test
    void rowtypeType_registration() {
        InlineTypeDefinition type = new InlineTypeDefinition(
                "emp_row_t",
                TypeCategory.ROWTYPE,
                null,
                List.of(
                        new FieldDefinition("empno", "NUMBER", "numeric"),
                        new FieldDefinition("ename", "VARCHAR2", "text")
                ),
                ConversionStrategy.JSONB,
                null
        );

        context.registerInlineType("emp_row_t", type);

        InlineTypeDefinition retrieved = context.getInlineType("emp_row_t");
        assertTrue(retrieved.isRecord());
        assertEquals(2, retrieved.getFields().size());
    }
}
