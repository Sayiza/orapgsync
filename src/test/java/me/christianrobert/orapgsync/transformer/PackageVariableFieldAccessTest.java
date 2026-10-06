package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for package variable field access transformation (Phase 2: Complex Types).
 *
 * <p>Tests the transformation of Oracle package variable RECORD field access
 * to PostgreSQL jsonb operations:
 * <ul>
 *   <li>pkg.g_rec.field → (pkg__get_g_rec()->>'field')::type</li>
 *   <li>g_rec.field (inside package) → (pkg__get_g_rec()->>'field')::type</li>
 *   <li>schema.pkg.g_rec.field → (schema.pkg__get_g_rec()->>'field')::type</li>
 * </ul>
 */
class PackageVariableFieldAccessTest {

    private AntlrParser parser;
    private Map<String, PackageContext> packageContextCache;
    private TransformationIndices emptyIndices;
    private SimpleTypeEvaluator typeEvaluator;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        packageContextCache = new HashMap<>();

        // Create package context with RECORD type and variable
        PackageContext empPkgContext = new PackageContext("hr", "emp_pkg");

        // Add RECORD type definition
        InlineTypeDefinition salaryRangeType = new InlineTypeDefinition(
            "salary_range_t",
            TypeCategory.RECORD,
            null,
            List.of(
                new FieldDefinition("min_sal", "NUMBER", "numeric"),
                new FieldDefinition("max_sal", "NUMBER", "numeric")
            ),
            ConversionStrategy.JSONB,
            null
        );
        empPkgContext.addType(salaryRangeType);

        // Add nested RECORD type for nested field access test
        InlineTypeDefinition addressType = new InlineTypeDefinition(
            "address_t",
            TypeCategory.RECORD,
            null,
            List.of(
                new FieldDefinition("street", "VARCHAR2(100)", "text"),
                new FieldDefinition("city", "VARCHAR2(50)", "text")
            ),
            ConversionStrategy.JSONB,
            null
        );
        empPkgContext.addType(addressType);

        InlineTypeDefinition employeeType = new InlineTypeDefinition(
            "employee_t",
            TypeCategory.RECORD,
            null,
            List.of(
                new FieldDefinition("name", "VARCHAR2(100)", "text"),
                new FieldDefinition("address", "address_t", "jsonb")
            ),
            ConversionStrategy.JSONB,
            null
        );
        empPkgContext.addType(employeeType);

        // Add package variables
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_range", "salary_range_t", null, false
        ));
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_employee", "employee_t", null, false
        ));
        // Add a scalar variable to ensure we don't break scalar access
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));

        // Add collection types for Phase 4 tests
        // TABLE OF NUMBER collection
        InlineTypeDefinition numListType = new InlineTypeDefinition(
            "num_list_t",
            TypeCategory.TABLE_OF,
            "NUMBER",  // Element type
            null,      // No fields for TABLE OF
            ConversionStrategy.JSONB,
            null
        );
        empPkgContext.addType(numListType);

        // INDEX BY VARCHAR2 (associative array / map)
        InlineTypeDefinition stringMapType = new InlineTypeDefinition(
            "string_map_t",
            TypeCategory.INDEX_BY,
            "VARCHAR2(100)",  // Value type
            null,
            ConversionStrategy.JSONB,
            null,
            "VARCHAR2(50)"    // Index key type (string-based)
        );
        empPkgContext.addType(stringMapType);

        // INDEX BY PLS_INTEGER (numeric array)
        InlineTypeDefinition numArrayType = new InlineTypeDefinition(
            "num_array_t",
            TypeCategory.INDEX_BY,
            "NUMBER",
            null,
            ConversionStrategy.JSONB,
            null,
            "PLS_INTEGER"     // Index key type (numeric)
        );
        empPkgContext.addType(numArrayType);

        // Add collection variables
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_numbers", "num_list_t", null, false
        ));
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_name_map", "string_map_t", null, false
        ));
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_scores", "num_array_t", null, false
        ));

        packageContextCache.put("hr.emp_pkg", empPkgContext);

        // Create empty transformation indices for testing
        emptyIndices = new TransformationIndices(
            Collections.emptyMap(),  // tableColumns
            Collections.emptyMap(),  // typeMethods
            Collections.emptySet(),  // packageFunctions
            Collections.emptyMap(),  // synonyms
            Collections.emptyMap(),  // typeFieldTypes
            Collections.emptySet()   // objectTypeNames
        );

        // Create simple type evaluator for testing
        typeEvaluator = new SimpleTypeEvaluator("hr", emptyIndices);
    }

    // =========================================================================
    // Package-qualified field access: pkg.g_rec.field
    // =========================================================================

    @Test
    void testPackageQualifiedFieldAccess() {
        // Oracle: emp_pkg.g_range.min_sal
        // PostgreSQL: (hr.emp_pkg__get_g_range()->>'min_sal')::numeric

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_range.min_sal;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parse should succeed");

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to getter with field access
        assertTrue(postgresSql.contains("emp_pkg__get_g_range()"),
            "Should use getter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>'min_sal'"),
            "Should access field with ->>. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("::numeric"),
            "Should cast to numeric. Actual: " + postgresSql);
    }

    @Test
    void testPackageQualifiedFieldAccessMaxSal() {
        // Test another field to ensure it's not hardcoded
        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_range.max_sal;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_range()"));
        assertTrue(postgresSql.contains("->>'max_sal'"));
        assertTrue(postgresSql.contains("::numeric"));
    }

    @Test
    void testPackageQualifiedStringField() {
        // Test VARCHAR2 field to ensure proper type cast
        String plsql = """
            FUNCTION test_func RETURN VARCHAR2 AS
              v_result VARCHAR2(100);
            BEGIN
              v_result := emp_pkg.g_employee.name;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_employee()"),
            "Should use getter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>'name'"),
            "Should access field with ->>. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("::text"),
            "Should cast to text. Actual: " + postgresSql);
    }

    // =========================================================================
    // Unqualified field access inside package: g_rec.field
    // =========================================================================

    @Test
    void testUnqualifiedFieldAccessInsidePackage() {
        // When inside emp_pkg, can access g_range.min_sal directly
        // Oracle: g_range.min_sal (inside emp_pkg function)
        // PostgreSQL: (hr.emp_pkg__get_g_range()->>'min_sal')::numeric

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := g_range.min_sal;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        // Context is inside emp_pkg
        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform using current package
        assertTrue(postgresSql.contains("emp_pkg__get_g_range()"),
            "Should use getter with current package. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>'min_sal'"),
            "Should access field. Actual: " + postgresSql);
    }

    // =========================================================================
    // Schema-qualified field access: schema.pkg.g_rec.field
    // =========================================================================

    @Test
    void testSchemaQualifiedFieldAccess() {
        // Oracle: hr.emp_pkg.g_range.min_sal
        // PostgreSQL: (hr.emp_pkg__get_g_range()->>'min_sal')::numeric

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := hr.emp_pkg.g_range.min_sal;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("hr.emp_pkg__get_g_range()"),
            "Should use schema-qualified getter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>'min_sal'"),
            "Should access field. Actual: " + postgresSql);
    }

    // =========================================================================
    // Nested field access: pkg.g_rec.nested.field
    // =========================================================================

    @Test
    void testNestedFieldAccess() {
        // Oracle: emp_pkg.g_employee.address.city
        // PostgreSQL: (hr.emp_pkg__get_g_employee()->'address'->>'city')::text

        String plsql = """
            FUNCTION test_func RETURN VARCHAR2 AS
              v_result VARCHAR2(50);
            BEGIN
              v_result := emp_pkg.g_employee.address.city;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_employee()"),
            "Should use getter. Actual: " + postgresSql);
        // Intermediate field should use -> (not ->>)
        assertTrue(postgresSql.contains("->'address'"),
            "Intermediate field should use ->. Actual: " + postgresSql);
        // Final field should use ->>
        assertTrue(postgresSql.contains("->>'city'"),
            "Final field should use ->>. Actual: " + postgresSql);
    }

    // =========================================================================
    // Scalar variables should still work
    // =========================================================================

    @Test
    void testScalarVariableStillWorks() {
        // Scalar variable access should not be broken
        // Oracle: emp_pkg.g_counter
        // PostgreSQL: hr.emp_pkg__get_g_counter()

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_counter;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to simple getter (no field access)
        assertTrue(postgresSql.contains("emp_pkg__get_g_counter()"),
            "Scalar should use getter. Actual: " + postgresSql);
        assertFalse(postgresSql.contains("->>"),
            "Scalar should not have field access. Actual: " + postgresSql);
    }

    // =========================================================================
    // Field access in expressions
    // =========================================================================

    @Test
    void testFieldAccessInArithmetic() {
        // Oracle: emp_pkg.g_range.min_sal + emp_pkg.g_range.max_sal
        // Both should transform to getter calls with field access

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_range.min_sal + emp_pkg.g_range.max_sal;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should have two getter calls with field access
        assertTrue(postgresSql.contains("->>'min_sal'"),
            "Should access min_sal. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>'max_sal'"),
            "Should access max_sal. Actual: " + postgresSql);
    }

    @Test
    void testFieldAccessInCondition() {
        // Oracle: IF emp_pkg.g_range.min_sal > 1000 THEN
        // Field access in IF condition

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              IF emp_pkg.g_range.min_sal > 1000 THEN
                NULL;
              END IF;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_range()"),
            "Condition should use getter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>'min_sal'"),
            "Condition should access field. Actual: " + postgresSql);
    }

    // =========================================================================
    // Field assignment (LHS): pkg.g_rec.field := value
    // =========================================================================

    @Test
    void testPackageQualifiedFieldAssignment() {
        // Oracle: emp_pkg.g_range.min_sal := 1000
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_range(jsonb_set(hr.emp_pkg__get_g_range(), '{min_sal}', to_jsonb(1000)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_range.min_sal := 1000;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parse should succeed");

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to PERFORM with setter wrapping jsonb_set
        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_range"),
            "Should use setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("jsonb_set"),
            "Should use jsonb_set. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__get_g_range()"),
            "Should get current value. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("min_sal"),
            "Should reference field name. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("to_jsonb"),
            "Should wrap value in to_jsonb. Actual: " + postgresSql);
    }

    @Test
    void testUnqualifiedFieldAssignmentInsidePackage() {
        // Oracle: g_range.min_sal := 2000 (inside emp_pkg function)
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_range(jsonb_set(hr.emp_pkg__get_g_range(), '{min_sal}', to_jsonb(2000)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              g_range.min_sal := 2000;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        // Context is inside emp_pkg
        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_range"),
            "Should use setter with current package. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("jsonb_set"),
            "Should use jsonb_set. Actual: " + postgresSql);
    }

    @Test
    void testSchemaQualifiedFieldAssignment() {
        // Oracle: hr.emp_pkg.g_range.max_sal := 5000
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_range(jsonb_set(hr.emp_pkg__get_g_range(), '{max_sal}', to_jsonb(5000)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              hr.emp_pkg.g_range.max_sal := 5000;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("hr.emp_pkg__set_g_range"),
            "Should use schema-qualified setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("max_sal"),
            "Should reference field name. Actual: " + postgresSql);
    }

    @Test
    void testNestedFieldAssignment() {
        // Oracle: emp_pkg.g_employee.address.city := 'New York'
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_employee(jsonb_set(hr.emp_pkg__get_g_employee(), '{address,city}', to_jsonb('New York'::text), true))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_employee.address.city := 'New York';
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_employee"),
            "Should use setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("jsonb_set"),
            "Should use jsonb_set. Actual: " + postgresSql);
        // Nested path should include both fields
        assertTrue(postgresSql.contains("address") && postgresSql.contains("city"),
            "Should include nested field path. Actual: " + postgresSql);
        // Nested paths should have 'true' flag for creating intermediate objects
        assertTrue(postgresSql.contains("true"),
            "Should have 'true' flag for nested path. Actual: " + postgresSql);
    }

    @Test
    void testFieldAssignmentWithExpression() {
        // Oracle: emp_pkg.g_range.min_sal := emp_pkg.g_range.max_sal / 2
        // Should use getter on RHS and setter with jsonb_set on LHS

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_range.min_sal := emp_pkg.g_range.max_sal / 2;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // LHS should use setter with jsonb_set
        assertTrue(postgresSql.contains("emp_pkg__set_g_range"),
            "LHS should use setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("jsonb_set"),
            "Should use jsonb_set. Actual: " + postgresSql);

        // RHS should have getter with field access for max_sal
        assertTrue(postgresSql.contains("->>'max_sal'"),
            "RHS should access max_sal field. Actual: " + postgresSql);
    }

    @Test
    void testStringFieldAssignment() {
        // Oracle: emp_pkg.g_employee.name := 'John Doe'
        // String literals need explicit ::text cast for to_jsonb()

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_employee.name := 'John Doe';
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__set_g_employee"),
            "Should use setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("to_jsonb"),
            "Should wrap in to_jsonb. Actual: " + postgresSql);
        // String literal should have ::text cast
        assertTrue(postgresSql.contains("'John Doe'::text"),
            "String literal should have ::text cast. Actual: " + postgresSql);
    }

    // =========================================================================
    // Collection element access (Phase 4): pkg.g_array(i)
    // =========================================================================

    @Test
    void testPackageQualifiedArrayElementAccess() {
        // Oracle: emp_pkg.g_numbers(1)
        // PostgreSQL: (hr.emp_pkg__get_g_numbers()->>0)::numeric

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_numbers(1);
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parse should succeed");

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to getter with array access
        assertTrue(postgresSql.contains("emp_pkg__get_g_numbers()"),
            "Should use getter. Actual: " + postgresSql);
        // Index should be 0-based (1 → 0)
        assertTrue(postgresSql.contains("->>0"),
            "Should use 0-based index. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("::numeric"),
            "Should cast to numeric. Actual: " + postgresSql);
    }

    @Test
    void testUnqualifiedArrayElementAccessInsidePackage() {
        // Oracle: g_numbers(2) (inside emp_pkg function)
        // PostgreSQL: (hr.emp_pkg__get_g_numbers()->>1)::numeric

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := g_numbers(2);
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        // Context is inside emp_pkg
        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform using current package
        assertTrue(postgresSql.contains("emp_pkg__get_g_numbers()"),
            "Should use getter with current package. Actual: " + postgresSql);
        // Index should be 0-based (2 → 1)
        assertTrue(postgresSql.contains("->>1"),
            "Should use 0-based index. Actual: " + postgresSql);
    }

    @Test
    void testSchemaQualifiedArrayElementAccess() {
        // Oracle: hr.emp_pkg.g_numbers(3)
        // PostgreSQL: (hr.emp_pkg__get_g_numbers()->>2)::numeric

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := hr.emp_pkg.g_numbers(3);
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("hr.emp_pkg__get_g_numbers()"),
            "Should use schema-qualified getter. Actual: " + postgresSql);
        // Index should be 0-based (3 → 2)
        assertTrue(postgresSql.contains("->>2"),
            "Should use 0-based index. Actual: " + postgresSql);
    }

    @Test
    void testArrayElementAccessWithVariable() {
        // Oracle: emp_pkg.g_numbers(i)
        // PostgreSQL: (hr.emp_pkg__get_g_numbers()->>((i - 1)::int))::numeric

        String plsql = """
            FUNCTION test_func(i NUMBER) RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_numbers(i);
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_numbers()"),
            "Should use getter. Actual: " + postgresSql);
        // Variable index should have subtraction
        assertTrue(postgresSql.contains("i - 1"),
            "Should subtract 1 from variable index. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("::int"),
            "Should cast index to int. Actual: " + postgresSql);
    }

    @Test
    void testMapElementAccessWithStringKey() {
        // Oracle: emp_pkg.g_name_map('key1')
        // PostgreSQL: (hr.emp_pkg__get_g_name_map() ->> 'key1')

        String plsql = """
            FUNCTION test_func RETURN VARCHAR2 AS
              v_result VARCHAR2(100);
            BEGIN
              v_result := emp_pkg.g_name_map('key1');
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_name_map()"),
            "Should use getter. Actual: " + postgresSql);
        // Map access should use ->> with string key (no index conversion)
        assertTrue(postgresSql.contains("->>") && postgresSql.contains("'key1'"),
            "Should use ->> with string key. Actual: " + postgresSql);
    }

    @Test
    void testNumericIndexByElementAccess() {
        // Oracle: emp_pkg.g_scores(5)
        // PostgreSQL: (hr.emp_pkg__get_g_scores()->>4)::numeric
        // INDEX BY PLS_INTEGER uses numeric indexing with conversion

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_scores(5);
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_scores()"),
            "Should use getter. Actual: " + postgresSql);
        // INDEX BY PLS_INTEGER uses object key access with string key (5 → '4')
        assertTrue(postgresSql.contains("->> '4'"),
            "Should use 0-based string key for INDEX BY PLS_INTEGER. Actual: " + postgresSql);
    }

    @Test
    void testArrayElementAccessInArithmetic() {
        // Oracle: emp_pkg.g_numbers(1) + emp_pkg.g_numbers(2)
        // Both should transform correctly

        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_numbers(1) + emp_pkg.g_numbers(2);
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_func", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should have two getter calls with array access
        assertTrue(postgresSql.contains("->>0"),
            "Should access element 0. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>1"),
            "Should access element 1. Actual: " + postgresSql);
    }

    @Test
    void testArrayElementAccessInCondition() {
        // Oracle: IF emp_pkg.g_numbers(1) > 100 THEN

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              IF emp_pkg.g_numbers(1) > 100 THEN
                NULL;
              END IF;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("emp_pkg__get_g_numbers()"),
            "Condition should use getter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("->>0"),
            "Should access element with 0-based index. Actual: " + postgresSql);
    }

    // =========================================================================
    // Collection element assignment (Phase 5): pkg.g_array(i) := value
    // =========================================================================

    @Test
    void testPackageQualifiedArrayElementAssignment() {
        // Oracle: emp_pkg.g_numbers(1) := 100
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_numbers(jsonb_set(hr.emp_pkg__get_g_numbers(), '{0}', to_jsonb(100)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_numbers(1) := 100;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess(), "Parse should succeed");

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to PERFORM with setter
        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_numbers"),
            "Should use setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("jsonb_set"),
            "Should use jsonb_set. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__get_g_numbers()"),
            "Should get current value. Actual: " + postgresSql);
        // Index should be 0-based with text[] cast
        assertTrue(postgresSql.contains("'{0}'::text[]"),
            "Should use 0-based index with text[] cast. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("to_jsonb"),
            "Should wrap value in to_jsonb. Actual: " + postgresSql);
    }

    @Test
    void testUnqualifiedArrayElementAssignmentInsidePackage() {
        // Oracle: g_numbers(2) := 200 (inside emp_pkg function)
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_numbers(jsonb_set(hr.emp_pkg__get_g_numbers(), '{1}', to_jsonb(200)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              g_numbers(2) := 200;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        // Context is inside emp_pkg
        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_numbers"),
            "Should use setter with current package. Actual: " + postgresSql);
        // Index should be 0-based (2 → 1) with text[] cast
        assertTrue(postgresSql.contains("'{1}'::text[]"),
            "Should use 0-based index with text[] cast. Actual: " + postgresSql);
    }

    @Test
    void testSchemaQualifiedArrayElementAssignment() {
        // Oracle: hr.emp_pkg.g_numbers(3) := 300
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_numbers(jsonb_set(hr.emp_pkg__get_g_numbers(), '{2}', to_jsonb(300)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              hr.emp_pkg.g_numbers(3) := 300;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("hr.emp_pkg__set_g_numbers"),
            "Should use schema-qualified setter. Actual: " + postgresSql);
        // Index should be 0-based (3 → 2) with text[] cast
        assertTrue(postgresSql.contains("'{2}'::text[]"),
            "Should use 0-based index with text[] cast. Actual: " + postgresSql);
    }

    @Test
    void testArrayElementAssignmentWithVariableIndex() {
        // Oracle: emp_pkg.g_numbers(i) := 500
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_numbers(jsonb_set(..., '{' || (i - 1) || '}', ...))

        String plsql = """
            PROCEDURE test_proc(i NUMBER) AS
            BEGIN
              emp_pkg.g_numbers(i) := 500;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_numbers"),
            "Should use setter. Actual: " + postgresSql);
        // Variable index should have subtraction
        assertTrue(postgresSql.contains("i - 1"),
            "Should subtract 1 from variable index. Actual: " + postgresSql);
    }

    @Test
    void testMapElementAssignmentWithStringKey() {
        // Oracle: emp_pkg.g_name_map('dept10') := 'Engineering'
        // PostgreSQL: PERFORM hr.emp_pkg__set_g_name_map(jsonb_set(..., '{dept10}', to_jsonb('Engineering'::text)))

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_name_map('dept10') := 'Engineering';
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_name_map"),
            "Should use setter. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("jsonb_set"),
            "Should use jsonb_set. Actual: " + postgresSql);
        // Map key should be used directly (no index conversion)
        assertTrue(postgresSql.contains("dept10"),
            "Should use string key. Actual: " + postgresSql);
        // String value should have ::text cast
        assertTrue(postgresSql.contains("'Engineering'::text"),
            "String value should have ::text cast. Actual: " + postgresSql);
    }

    @Test
    void testArrayElementAssignmentWithExpression() {
        // Oracle: emp_pkg.g_numbers(1) := emp_pkg.g_numbers(2) * 2
        // RHS should use getter with element access, LHS should use setter with jsonb_set

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_numbers(1) := emp_pkg.g_numbers(2) * 2;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        // LHS should use setter with jsonb_set
        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_numbers"),
            "Should use setter. Actual: " + postgresSql);
        // RHS should have getter with element access (index 2 → 1)
        assertTrue(postgresSql.contains("->>1") && postgresSql.contains("* 2"),
            "RHS should access element 1 and multiply. Actual: " + postgresSql);
    }

    @Test
    void testNumericIndexByElementAssignment() {
        // Oracle: emp_pkg.g_scores(10) := 95
        // INDEX BY PLS_INTEGER uses numeric indexing with conversion

        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_scores(10) := 95;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext(
            "hr", emptyIndices, typeEvaluator,
            packageContextCache, "test_proc", "emp_pkg"
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM"),
            "Should use PERFORM. Actual: " + postgresSql);
        assertTrue(postgresSql.contains("emp_pkg__set_g_scores"),
            "Should use setter. Actual: " + postgresSql);
        // Index should be 0-based (10 → 9) with text[] cast
        assertTrue(postgresSql.contains("'{9}'::text[]"),
            "Should use 0-based index with text[] cast. Actual: " + postgresSql);
    }
}
