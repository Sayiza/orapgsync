package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PackageContextExtractor TYPE declaration extraction.
 * Tests parsing of RECORD, TABLE OF, VARRAY, and INDEX BY types from package specs.
 */
class PackageContextExtractorTypeTest {

    private PackageContextExtractor extractor;
    private AntlrParser antlrParser;

    @BeforeEach
    void setUp() {
        antlrParser = new AntlrParser();
        extractor = new PackageContextExtractor(antlrParser);
    }

    @Test
    void extractSimpleRecordType() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE salary_range_t IS RECORD (
                min_sal NUMBER,
                max_sal NUMBER
              );
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());
        assertTrue(context.hasType("salary_range_t"));

        InlineTypeDefinition type = context.getType("salary_range_t");
        assertNotNull(type);
        assertEquals("salary_range_t", type.getTypeName());
        assertEquals(TypeCategory.RECORD, type.getCategory());
        assertEquals(2, type.getFields().size());
        assertEquals("min_sal", type.getFields().get(0).getFieldName());
        assertEquals("max_sal", type.getFields().get(1).getFieldName());
        assertTrue(type.isRecord());
        assertFalse(type.isCollection());
    }

    @Test
    void extractRecordTypeWithMixedTypes() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE employee_rec_t IS RECORD (
                empno NUMBER,
                ename VARCHAR2(100),
                hire_date DATE,
                salary NUMBER(10,2)
              );
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());
        InlineTypeDefinition type = context.getType("employee_rec_t");

        assertNotNull(type);
        assertEquals(TypeCategory.RECORD, type.getCategory());
        assertEquals(4, type.getFields().size());

        // Verify field names
        assertEquals("empno", type.getFields().get(0).getFieldName());
        assertEquals("ename", type.getFields().get(1).getFieldName());
        assertEquals("hire_date", type.getFields().get(2).getFieldName());
        assertEquals("salary", type.getFields().get(3).getFieldName());

        // Verify PostgreSQL type conversion
        assertEquals("numeric", type.getFields().get(0).getPostgresType());
        assertEquals("text", type.getFields().get(1).getPostgresType());
        assertEquals("timestamp", type.getFields().get(2).getPostgresType());
    }

    @Test
    void extractTableOfType() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE num_list_t IS TABLE OF NUMBER;
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());
        assertTrue(context.hasType("num_list_t"));

        InlineTypeDefinition type = context.getType("num_list_t");
        assertNotNull(type);
        assertEquals("num_list_t", type.getTypeName());
        assertEquals(TypeCategory.TABLE_OF, type.getCategory());
        assertEquals("NUMBER", type.getElementType());
        assertNull(type.getFields());
        assertTrue(type.isCollection());
        assertTrue(type.isIndexedCollection());
        assertFalse(type.isAssociativeArray());
    }

    @Test
    void extractVarrayType() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());
        InlineTypeDefinition type = context.getType("codes_t");

        assertNotNull(type);
        assertEquals("codes_t", type.getTypeName());
        assertEquals(TypeCategory.VARRAY, type.getCategory());
        assertEquals("VARCHAR2(10)", type.getElementType());
        assertEquals(10, type.getSizeLimit());
        assertTrue(type.isCollection());
        assertTrue(type.isIndexedCollection());
    }

    @Test
    void extractIndexByType() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());
        InlineTypeDefinition type = context.getType("dept_map_t");

        assertNotNull(type);
        assertEquals("dept_map_t", type.getTypeName());
        assertEquals(TypeCategory.INDEX_BY, type.getCategory());
        assertEquals("VARCHAR2(100)", type.getElementType());
        assertEquals("VARCHAR2(50)", type.getIndexKeyType());
        assertTrue(type.isCollection());
        assertTrue(type.isAssociativeArray());
        assertFalse(type.isIndexedCollection());
    }

    @Test
    void extractIndexByWithIntegerKey() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE emp_list_t IS TABLE OF VARCHAR2(100) INDEX BY PLS_INTEGER;
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());
        InlineTypeDefinition type = context.getType("emp_list_t");

        assertNotNull(type);
        assertEquals(TypeCategory.INDEX_BY, type.getCategory());
        assertEquals("VARCHAR2(100)", type.getElementType());
        assertEquals("PLS_INTEGER", type.getIndexKeyType());
    }

    @Test
    void extractMultipleTypes() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE salary_range_t IS RECORD (
                min_sal NUMBER,
                max_sal NUMBER
              );
              TYPE num_list_t IS TABLE OF NUMBER;
              TYPE codes_t IS VARRAY(5) OF VARCHAR2(10);
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(4, context.getTypes().size());

        assertTrue(context.hasType("salary_range_t"));
        assertTrue(context.hasType("num_list_t"));
        assertTrue(context.hasType("codes_t"));
        assertTrue(context.hasType("dept_map_t"));

        assertEquals(TypeCategory.RECORD, context.getType("salary_range_t").getCategory());
        assertEquals(TypeCategory.TABLE_OF, context.getType("num_list_t").getCategory());
        assertEquals(TypeCategory.VARRAY, context.getType("codes_t").getCategory());
        assertEquals(TypeCategory.INDEX_BY, context.getType("dept_map_t").getCategory());
    }

    @Test
    void extractTypesAndVariables() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              g_counter INTEGER := 0;

              TYPE salary_range_t IS RECORD (
                min_sal NUMBER,
                max_sal NUMBER
              );

              g_default_range salary_range_t;

              TYPE num_list_t IS TABLE OF NUMBER;
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        // Verify both variables and types extracted
        assertEquals(2, context.getVariables().size());
        assertEquals(2, context.getTypes().size());

        assertTrue(context.hasVariable("g_counter"));
        assertTrue(context.hasVariable("g_default_range"));
        assertTrue(context.hasType("salary_range_t"));
        assertTrue(context.hasType("num_list_t"));
    }

    @Test
    void extractTypesCaseInsensitive() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE SALARY_RANGE_T IS RECORD (
                MIN_SAL NUMBER,
                MAX_SAL NUMBER
              );
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(1, context.getTypes().size());

        // Should find with different case variations
        assertTrue(context.hasType("SALARY_RANGE_T"));
        assertTrue(context.hasType("salary_range_t"));
        assertTrue(context.hasType("Salary_Range_T"));

        assertNotNull(context.getType("SALARY_RANGE_T"));
        assertNotNull(context.getType("salary_range_t"));
    }

    @Test
    void extractEmptyPackageSpec() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        assertEquals(0, context.getTypes().size());
        assertEquals(0, context.getVariables().size());
    }

    @Test
    void extractTypeInitializers() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE point_t IS RECORD (x NUMBER, y NUMBER);
              TYPE nums_t IS TABLE OF NUMBER;
              TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
              TYPE map_t IS TABLE OF NUMBER INDEX BY VARCHAR2(50);
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        // Verify initializers are correct for each type
        assertEquals("'{}'::jsonb", context.getType("point_t").getInitializer());
        assertEquals("'[]'::jsonb", context.getType("nums_t").getInitializer());
        assertEquals("'[]'::jsonb", context.getType("codes_t").getInitializer());
        assertEquals("'{}'::jsonb", context.getType("map_t").getInitializer());
    }

    @Test
    void extractTypePostgresTypes() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE test_pkg AS
              TYPE point_t IS RECORD (x NUMBER, y NUMBER);
              TYPE nums_t IS TABLE OF NUMBER;
            END test_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "test_pkg", packageSpec);

        // All types should return jsonb in Phase 1
        assertEquals("jsonb", context.getType("point_t").getPostgresType());
        assertEquals("jsonb", context.getType("nums_t").getPostgresType());
    }
}
