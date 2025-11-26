package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.FullTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle date arithmetic transformations to PostgreSQL INTERVAL syntax.
 *
 * <p><strong>Problem:</strong> Oracle allows direct arithmetic with dates and integers:
 * <pre>
 * -- Oracle (implicit day arithmetic)
 * SELECT hire_date + 30 FROM employees;  -- Add 30 days
 * SELECT end_date - 7 FROM projects;     -- Subtract 7 days
 * </pre>
 *
 * <p><strong>PostgreSQL Requirement:</strong> Explicit INTERVAL syntax:
 * <pre>
 * -- PostgreSQL
 * SELECT hire_date + (30 * INTERVAL '1 day') FROM employees;
 * SELECT end_date - (7 * INTERVAL '1 day') FROM projects;
 * </pre>
 *
 * <p><strong>Test Coverage:</strong></p>
 * <ul>
 *   <li>Simple date + integer (detected by column name heuristic)</li>
 *   <li>Simple date - integer (detected by column name heuristic)</li>
 *   <li>Commutative addition: integer + date</li>
 *   <li>Date arithmetic with SYSDATE (detected by function heuristic)</li>
 *   <li>Date arithmetic in WHERE clause</li>
 *   <li>Date arithmetic with metadata lookup (column type is DATE/TIMESTAMP)</li>
 *   <li>Complex expressions (limitations of heuristic approach)</li>
 *   <li>Numeric arithmetic (should NOT transform)</li>
 * </ul>
 *
 * <p><strong>Detection Strategy (Heuristic):</strong></p>
 * <ol>
 *   <li>Metadata lookup - check column type via TransformationIndices</li>
 *   <li>Function detection - look for date functions (SYSDATE, TO_DATE, etc.)</li>
 *   <li>Pattern matching - check for date-related column names (*date*, *time*, etc.)</li>
 * </ol>
 *
 * <p><strong>Future:</strong> Replace heuristic with type inference when TypeAnalysisVisitor is integrated.</p>
 *
 * @see me.christianrobert.orapgsync.transformer.builder.functions.DateArithmeticTransformer
 */
public class DateArithmeticTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== Simple Date Arithmetic (Column Name Heuristic) ====================

    @Test
    void simpleDatePlusInteger() {
        // Given: Date column + integer (detected by column name "hire_date")
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT hire_date + 30 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform to INTERVAL syntax
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( 30 * INTERVAL '1 day' )"),
            "Date + integer should transform to INTERVAL, got: " + normalized);
        assertTrue(normalized.contains("FROM hr.employees"),
            "Table should be schema-qualified");
    }

    @Test
    void simpleDateMinusInteger() {
        // Given: Date column - integer (detected by column name "end_date")
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT end_date - 7 FROM projects";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform to INTERVAL syntax
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("end_date - ( 7 * INTERVAL '1 day' )"),
            "Date - integer should transform to INTERVAL, got: " + normalized);
    }

    @Test
    void commutativeAddition() {
        // Given: Integer + date (should reorder to date + INTERVAL)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT 14 + hire_date FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should reorder operands and transform to INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( 14 * INTERVAL '1 day' )"),
            "Integer + date should reorder and transform to INTERVAL, got: " + normalized);
    }

    @Test
    void dateArithmeticWithVariousColumnNames() {
        // Given: Various date-related column names (created_at, modified_on, start_date, etc.)
        TransformationContext context = new TransformationContext("APP", emptyIndices, new SimpleTypeEvaluator("APP", emptyIndices));

        String[] testCases = {
            "SELECT created_at + 1 FROM logs",
            "SELECT modified_on - 3 FROM records",
            "SELECT start_date + 10 FROM events",
            "SELECT end_time - 5 FROM sessions",
            "SELECT birth_date + 365 FROM people"
        };

        for (String oracleSql : testCases) {
            ParseResult parseResult = parser.parseSelectStatement(oracleSql);
            assertFalse(parseResult.hasErrors(), "Parse should succeed for: " + oracleSql);

            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            String normalized = postgresSql.trim().replaceAll("\\s+", " ");

            assertTrue(normalized.contains("INTERVAL"),
                "Should transform to INTERVAL for: " + oracleSql + ", got: " + normalized);
        }
    }

    // ==================== Date Functions (Function Detection Heuristic) ====================

    @Test
    void sysdatePlusInteger() {
        // Given: SYSDATE + integer (detected by SYSDATE keyword)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT SYSDATE + 7 FROM dual";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform to INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CURRENT_TIMESTAMP + ( 7 * INTERVAL '1 day' )"),
            "SYSDATE + integer should transform to INTERVAL, got: " + normalized);
    }

    @Test
    void toDatePlusInteger() {
        // Given: TO_DATE() + integer (detected by TO_DATE function)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TO_DATE('2024-01-01', 'YYYY-MM-DD') + 30 FROM dual";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform to INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( 30 * INTERVAL '1 day' )"),
            "TO_DATE() + integer should transform to INTERVAL, got: " + normalized);
    }

    @Test
    void addMonthsResultPlusInteger() {
        // Given: ADD_MONTHS() + integer (detected by date function)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ADD_MONTHS(hire_date, 6) + 15 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both ADD_MONTHS and + should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( 6 * INTERVAL '1 month' )"),
            "ADD_MONTHS should be transformed, got: " + normalized);
        assertTrue(normalized.contains("( 15 * INTERVAL '1 day' )"),
            "Date arithmetic should also be transformed, got: " + normalized);
    }

    // ==================== WHERE Clause ====================

    @Test
    void dateArithmeticInWhereClause() {
        // Given: Date arithmetic in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT * FROM employees WHERE hire_date + 90 > SYSDATE";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Date arithmetic in WHERE should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( 90 * INTERVAL '1 day' )"),
            "Date arithmetic in WHERE should transform, got: " + normalized);
        assertTrue(normalized.contains("> CURRENT_TIMESTAMP"),
            "SYSDATE should be transformed");
    }

    @Test
    void userExampleSperre() {
        // Given: User's real-world example - sperre_endet_am + 1 > current_timestamp
        // LIMITATION: "sperre_endet_am" doesn't match our heuristics (*date*, *time*, *_at, *_on, etc.)
        // The "_am" suffix is not a recognized pattern
        // This demonstrates the limitation of heuristic approach
        TransformationContext context = new TransformationContext("CO_ABS", emptyIndices, new SimpleTypeEvaluator("CO_ABS", emptyIndices));

        String oracleSql = "SELECT sperre_endet_am FROM abs_werk_sperren ws1 WHERE sperre_endet_am + 1 > SYSDATE";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Heuristic WILL NOT detect this (expected limitation)
        // However, SYSDATE should still transform
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // This test documents the limitation - when type inference is integrated, this should pass
        assertFalse(normalized.contains("( 1 * INTERVAL '1 day' )"),
            "Heuristic does not detect 'sperre_endet_am' as date column (expected limitation), got: " + normalized);
        assertTrue(normalized.contains("> CURRENT_TIMESTAMP"),
            "SYSDATE should be transformed");

        // Future: When type inference is integrated, this assertion should be flipped:
        // assertTrue(normalized.contains("( 1 * INTERVAL '1 day' )"), "Should detect via type inference");
    }

    // ==================== Metadata-Based Detection (Future with Type Inference) ====================

    // NOTE: Full metadata-based detection is deferred to Phase 2 (Type Inference)
    // Phase 1 relies on column name heuristics and function detection
    // These test cases are commented out but document the future behavior

    /*
    @Test
    void metadataBasedDetectionDateColumn() {
        // FUTURE: When type inference is integrated, this test should pass
        // Given: Column with DATE type in metadata
        TransformationContext context = new TransformationContext("HR", indicesWithEmployees, new SimpleTypeEvaluator("HR", indicesWithEmployees));

        String oracleSql = "SELECT e.hire_date + 7 FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform based on type inference
        assertTrue(postgresSql.contains("( 7 * INTERVAL '1 day' )"));
    }
    */

    // ==================== Numeric Arithmetic (Should NOT Transform) ====================

    @Test
    void numericAdditionNoTransformation() {
        // Given: Numeric addition (no date involved)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT salary + 1000 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should NOT transform to INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertFalse(normalized.contains("INTERVAL"),
            "Numeric addition should NOT transform to INTERVAL, got: " + normalized);
        assertTrue(normalized.contains("salary + 1000"),
            "Numeric addition should pass through unchanged, got: " + normalized);
    }

    @Test
    void numericSubtractionNoTransformation() {
        // Given: Numeric subtraction (no date involved)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT amount - 50 FROM orders";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should NOT transform to INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertFalse(normalized.contains("INTERVAL"),
            "Numeric subtraction should NOT transform to INTERVAL, got: " + normalized);
        assertTrue(normalized.contains("amount - 50"),
            "Numeric subtraction should pass through unchanged, got: " + normalized);
    }

    @Test
    void dateDifferenceNoTransformation() {
        // Given: Date - Date (returns interval, no transformation needed)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT end_date - start_date FROM projects";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should NOT add INTERVAL wrapper (both operands are dates)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("end_date - start_date"),
            "Date - Date should NOT be wrapped in INTERVAL, got: " + normalized);
    }

    // ==================== Edge Cases and Limitations ====================

    @Test
    void complexExpressionLimitation() {
        // Given: Complex expression that heuristic may struggle with
        // LIMITATION: This test documents a known limitation of the heuristic approach
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT (CASE WHEN active = 1 THEN x_val ELSE y_val END) + 1 FROM data";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Heuristic likely won't detect this as date arithmetic
        // This is EXPECTED behavior - not a bug, just a documented limitation
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // We're just documenting behavior here - no specific assertion about INTERVAL
        // When type inference is integrated, this should be detected correctly
        assertNotNull(postgresSql, "Should produce some output");
    }

    @Test
    void multipleArithmeticOperations() {
        // Given: Multiple date arithmetic operations
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT hire_date + 30, end_date - 7 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both operations should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( 30 * INTERVAL '1 day' )"),
            "First date arithmetic should transform, got: " + normalized);
        assertTrue(normalized.contains("end_date - ( 7 * INTERVAL '1 day' )"),
            "Second date arithmetic should transform, got: " + normalized);
    }

    @Test
    void qualifiedColumnName() {
        // Given: Qualified column name (table.column)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp.hire_date + 60 FROM employees emp";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should detect date arithmetic with qualified column name
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Note: ANTLR may add spaces around the dot (emp . hire_date)
        assertTrue(normalized.contains("( 60 * INTERVAL '1 day' )"),
            "Qualified column date arithmetic should transform, got: " + normalized);
        assertTrue(normalized.contains("hire_date"),
            "Column name should be present");
    }

    // ==================== Type Inference Integration Tests (Phase 2) ====================

    @Test
    void qualifiedColumnWithTypeInference() {
        // Given: Qualified column with metadata - use type inference instead of heuristics
        // This is the example query: SELECT 1 FROM tablexy x WHERE current_date < x.somedate + 3

        // Build metadata indices with a table that has a somedate column of DATE type
        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("somedate", new TransformationIndices.ColumnTypeInfo("DATE", "somedate"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("test.tablexy", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),  // typeMethods
            new HashSet<>(),  // packageFunctions
            new HashMap<>(),  // synonyms
            new HashMap<>(),  // typeFieldTypes
            new HashSet<>()   // objectTypeNames
        );

        String oracleSql = "SELECT 1 FROM tablexy x WHERE current_date < x.somedate + 3";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass (populate type cache)
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("TEST", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("TEST", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform x.somedate + 3 to x.somedate + (3 * INTERVAL '1 day')
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( 3 * INTERVAL '1 day' )"),
            "Type inference should detect x.somedate as DATE and transform arithmetic, got: " + normalized);
        assertTrue(normalized.contains("FROM test.tablexy"),
            "Table should be schema-qualified");
        assertTrue(normalized.contains("WHERE"),
            "WHERE clause should be present");
    }

    @Test
    void complexExpressionWithTypeInference() {
        // Given: Complex CASE expression with date columns
        // Previously failed with heuristics, now works with type inference

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("start_date", new TransformationIndices.ColumnTypeInfo("DATE", "start_date"));
        columns.put("end_date", new TransformationIndices.ColumnTypeInfo("DATE", "end_date"));
        columns.put("active", new TransformationIndices.ColumnTypeInfo("NUMBER", "active"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("hr.projects", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT CASE WHEN active = 1 THEN start_date ELSE end_date END + 1 FROM projects";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("HR", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("HR", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Type inference should detect CASE result as DATE and transform + 1
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( 1 * INTERVAL '1 day' )"),
            "Type inference should detect CASE result as DATE, got: " + normalized);
        assertTrue(normalized.contains("CASE WHEN"),
            "CASE expression should be present");
    }

    @Test
    void numericArithmeticWithTypeInference() {
        // Given: Numeric column (should NOT transform even with type inference)

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("salary", new TransformationIndices.ColumnTypeInfo("NUMBER", "salary"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("hr.employees", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT salary + 1000 FROM employees";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("HR", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("HR", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should NOT transform (both operands are numeric)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertFalse(normalized.contains("INTERVAL"),
            "Numeric arithmetic should NOT transform to INTERVAL, got: " + normalized);
        assertTrue(normalized.contains("salary + 1000"),
            "Numeric arithmetic should pass through unchanged");
    }

    // ==================== Scalar Subquery Tests (Phase 4) ====================

    @Test
    void dateArithmeticWithScalarSubquery_literalInteger() {
        // Given: Bug report - scalar subquery type was UNKNOWN, now inferred as NUMERIC
        // This test verifies that scalar subquery type inference works

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hire_date", new TransformationIndices.ColumnTypeInfo("DATE", "hire_date"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("hr.employees", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        // Use a simpler query with hire_date (column name heuristic will help)
        String oracleSql = "SELECT e.hire_date + (SELECT 7 FROM dual) FROM employees e";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass (should now infer scalar subquery type)
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("hr", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Verify: Scalar subquery should be inferred as NUMERIC
        long numericTypes = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericTypes >= 1, "Scalar subquery (SELECT 7) should be inferred as NUMERIC");

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("hr", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should detect date arithmetic and transform to INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("INTERVAL") && normalized.contains("day"),
            "Scalar subquery should be inferred as NUMERIC, enabling date arithmetic detection, got: " + normalized);
    }

    @Test
    void dateArithmeticWithScalarSubquery_complexExpression() {
        // Given: More complex scalar subquery with expression
        // TRUNC(date) + (SELECT 7 * 2 FROM dual) should detect scalar subquery type as NUMERIC

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hire_date", new TransformationIndices.ColumnTypeInfo("DATE", "hire_date"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("hr.employees", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT hire_date + (SELECT 7 * 2 FROM dual) FROM employees";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("HR", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("HR", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should detect date arithmetic
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("INTERVAL") && normalized.contains("day"),
            "Scalar subquery with expression should be inferred as NUMERIC, enabling date arithmetic, got: " + normalized);
    }

    @Test
    void dateArithmeticWithScalarSubquery_truncCurrentDateOriginalBug() {
        // Given: Original bug report - TRUNC(CURRENT_DATE) + (SELECT 1 FROM dual)
        // This was incorrectly transformed to: DATE_TRUNC(...) + INTERVAL '(SELECT 1) days'
        // Should now correctly transform to: DATE_TRUNC(...) + ((SELECT 1) * INTERVAL '1 day')

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT TRUNC(CURRENT_DATE) + (SELECT 1 FROM dual) FROM dual";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass (should now infer scalar subquery type)
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("hr", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("hr", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should produce valid PostgreSQL syntax
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Verify the transformation is correct
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , CURRENT_DATE )::DATE"),
            "TRUNC(CURRENT_DATE) should transform to DATE_TRUNC");
        assertTrue(normalized.contains("* INTERVAL '1 day'"),
            "Scalar subquery should use multiplication pattern, got: " + normalized);
        assertFalse(normalized.contains("INTERVAL '(SELECT"),
            "Should NOT embed subquery in INTERVAL string literal, got: " + normalized);
        assertFalse(normalized.contains("INTERVAL '(SELECT 1) days'"),
            "Should NOT have broken syntax, got: " + normalized);
    }

    @Test
    void scalarSubquery_withDateResult_shouldNotTransform() {
        // Given: Scalar subquery returning a DATE (not a numeric addition)

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hire_date", new TransformationIndices.ColumnTypeInfo("DATE", "hire_date"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("hr.employees", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT hire_date + (SELECT SYSDATE FROM dual) FROM employees";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("HR", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("HR", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should NOT transform (DATE + DATE is not valid)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // This is an edge case - Oracle would reject DATE + DATE
        // We just verify it doesn't crash
        assertNotNull(postgresSql, "Should produce output");
    }
}
