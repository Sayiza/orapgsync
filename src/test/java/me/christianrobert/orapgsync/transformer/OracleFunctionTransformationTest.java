package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle-specific function transformations to PostgreSQL equivalents.
 *
 * <p>This test class covers:
 * <ul>
 *   <li>NVL → COALESCE transformation</li>
 *   <li>SYSDATE → CURRENT_TIMESTAMP transformation</li>
 *   <li>DECODE → CASE WHEN transformation</li>
 * </ul>
 */
public class OracleFunctionTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== NVL Function Tests ====================

    @Test
    void nvlWithNumericLiteral() {
        // Given: NVL with numeric default value
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(commission, 0) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL should be transformed to COALESCE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE("), "NVL should be transformed to COALESCE");
        assertTrue(normalized.contains("commission"), "Commission column should be present");
        assertTrue(normalized.contains("0"), "Default value 0 should be present");
        assertTrue(normalized.contains("FROM hr.employees"), "Table should be schema-qualified");
    }

    @Test
    void nvlWithColumnReference() {
        // Given: NVL with column as default value
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(middle_name, first_name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL should be transformed to COALESCE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE("), "NVL should be transformed to COALESCE");
        assertTrue(normalized.contains("middle_name"), "middle_name column should be present");
        assertTrue(normalized.contains("first_name"), "first_name column should be present");
    }

    @Test
    void nvlWithStringLiteral() {
        // Given: NVL with string literal default
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(department_name, 'Unknown') FROM departments";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL should be transformed to COALESCE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE("), "NVL should be transformed to COALESCE");
        assertTrue(normalized.contains("department_name"), "department_name should be present");
        assertTrue(normalized.contains("'Unknown'"), "String literal 'Unknown' should be present");
    }

    @Test
    void nvlInWhereClause() {
        // Given: NVL used in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE NVL(commission, 0) > 1000";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL should be transformed to COALESCE in WHERE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE"), "WHERE clause should be present");
        assertTrue(normalized.contains("COALESCE("), "NVL should be transformed to COALESCE");
        assertTrue(normalized.contains("commission"), "commission should be present");
        assertTrue(normalized.contains("> 1000"), "Comparison should be preserved");
    }

    @Test
    void nestedNvl() {
        // Given: Nested NVL calls
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(NVL(middle_name, first_name), 'N/A') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both NVL calls should be transformed to COALESCE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Count COALESCE occurrences (should be 2 for nested NVL)
        int coalesceCount = normalized.split("COALESCE\\(", -1).length - 1;
        assertEquals(2, coalesceCount, "Nested NVL should produce 2 COALESCE calls");

        assertTrue(normalized.contains("middle_name"), "middle_name should be present");
        assertTrue(normalized.contains("first_name"), "first_name should be present");
        assertTrue(normalized.contains("'N/A'"), "'N/A' should be present");
    }

    @Test
    void nvlWithArithmeticExpression() {
        // Given: NVL with arithmetic expression
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(commission, salary * 0.1) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL should be transformed to COALESCE with arithmetic preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE("), "NVL should be transformed to COALESCE");
        assertTrue(normalized.contains("commission"), "commission should be present");
        assertTrue(normalized.contains("salary"), "salary should be present");
        assertTrue(normalized.contains("*"), "Multiplication should be present");
        assertTrue(normalized.contains("0.1"), "Multiplier should be present");
    }

    @Test
    void multipleNvlInSelectList() {
        // Given: Multiple NVL functions in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(commission, 0), NVL(bonus, 0) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both NVL calls should be transformed to COALESCE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Count COALESCE occurrences (should be 2)
        int coalesceCount = normalized.split("COALESCE\\(", -1).length - 1;
        assertEquals(2, coalesceCount, "Two NVL calls should produce 2 COALESCE calls");

        assertTrue(normalized.contains("commission"), "commission should be present");
        assertTrue(normalized.contains("bonus"), "bonus should be present");
    }

    @Test
    void nvlInOrderByClause() {
        // Given: NVL in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY NVL(commission, 0) DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL should be transformed to COALESCE in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY"), "ORDER BY should be present");
        assertTrue(normalized.contains("COALESCE("), "NVL should be transformed to COALESCE");
        assertTrue(normalized.contains("commission"), "commission should be present");
        assertTrue(normalized.contains("DESC NULLS FIRST"), "DESC should have NULLS FIRST");
    }

    // ==================== SYSDATE Function Tests ====================

    @Test
    void sysdateInSelectList() {
        // Given: SYSDATE in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT SYSDATE FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SYSDATE should be transformed to CURRENT_TIMESTAMP
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"), "SYSDATE should be transformed to CURRENT_TIMESTAMP");
        assertTrue(normalized.contains("FROM hr.employees"), "Table should be schema-qualified");
        assertFalse(normalized.contains("SYSDATE"), "SYSDATE should not appear in output");
    }

    @Test
    void sysdateWithOtherColumns() {
        // Given: SYSDATE mixed with other columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, SYSDATE, ename FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SYSDATE should be transformed to CURRENT_TIMESTAMP
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno"), "empno should be present");
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"), "SYSDATE should be transformed to CURRENT_TIMESTAMP");
        assertTrue(normalized.contains("ename"), "ename should be present");
        assertFalse(normalized.contains("SYSDATE"), "SYSDATE should not appear in output");
    }

    @Test
    void sysdateInWhereClause() {
        // Given: SYSDATE in WHERE clause for date comparison
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE hire_date < SYSDATE";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SYSDATE should be transformed to CURRENT_TIMESTAMP in WHERE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE"), "WHERE clause should be present");
        assertTrue(normalized.contains("hire_date < CURRENT_TIMESTAMP"),
            "SYSDATE should be transformed to CURRENT_TIMESTAMP in comparison");
        assertFalse(normalized.contains("SYSDATE"), "SYSDATE should not appear in output");
    }

    @Test
    void sysdateCaseInsensitive() {
        // Given: SYSDATE in different cases (sysdate, SysDate, etc.)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT sysdate FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Lowercase sysdate should also be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"), "Lowercase sysdate should be transformed");
        assertFalse(normalized.toLowerCase().contains("sysdate"), "sysdate should not appear in output");
    }

    @Test
    void sysdateInArithmeticExpression() {
        // Given: SYSDATE used in arithmetic (date arithmetic)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE hire_date > SYSDATE - 30";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SYSDATE should be transformed in arithmetic expression
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"), "SYSDATE should be transformed");
        assertTrue(normalized.contains("- 30"), "Subtraction should be preserved");
        assertTrue(normalized.contains("hire_date > CURRENT_TIMESTAMP - 30"),
            "Arithmetic expression should be correct");
    }

    @Test
    void sysdateInOrderByClause() {
        // Given: SYSDATE in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY SYSDATE DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SYSDATE should be transformed in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY CURRENT_TIMESTAMP DESC NULLS FIRST"),
            "SYSDATE should be transformed in ORDER BY with proper NULL handling");
    }

    // ==================== DECODE Function Tests ====================

    @Test
    void decodeSimpleThreeArgs() {
        // Given: DECODE with 3 arguments (no default)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(status, 'A', 'Active') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should be transformed to CASE WHEN without ELSE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE status"), "Should have CASE with expression");
        assertTrue(normalized.contains("WHEN 'A'"), "Should have WHEN clause");
        assertTrue(normalized.contains("THEN 'Active'"), "Should have THEN clause");
        assertTrue(normalized.contains("END"), "Should have END keyword");
        assertFalse(normalized.contains("ELSE"), "Should NOT have ELSE clause (no default)");
    }

    @Test
    void decodeWithDefault() {
        // Given: DECODE with 4 arguments (with default)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(status, 'A', 'Active', 'Inactive') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should be transformed to CASE WHEN with ELSE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE status"), "Should have CASE with expression");
        assertTrue(normalized.contains("WHEN 'A'"), "Should have WHEN clause");
        assertTrue(normalized.contains("THEN 'Active'"), "Should have THEN clause");
        assertTrue(normalized.contains("ELSE 'Inactive'"), "Should have ELSE clause with default");
        assertTrue(normalized.contains("END"), "Should have END keyword");
    }

    @Test
    void decodeMultiplePairs() {
        // Given: DECODE with multiple search/result pairs and default
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(deptno, 10, 'Sales', 20, 'Marketing', 30, 'IT', 'Other') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should be transformed to CASE WHEN with multiple WHEN clauses
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE deptno"), "Should have CASE with expression");
        assertTrue(normalized.contains("WHEN 10 THEN 'Sales'"), "Should have first WHEN/THEN");
        assertTrue(normalized.contains("WHEN 20 THEN 'Marketing'"), "Should have second WHEN/THEN");
        assertTrue(normalized.contains("WHEN 30 THEN 'IT'"), "Should have third WHEN/THEN");
        assertTrue(normalized.contains("ELSE 'Other'"), "Should have ELSE clause with default");
        assertTrue(normalized.contains("END"), "Should have END keyword");
    }

    @Test
    void decodeMultiplePairsNoDefault() {
        // Given: DECODE with multiple search/result pairs but no default
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(deptno, 10, 'Sales', 20, 'Marketing', 30, 'IT') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should be transformed to CASE WHEN with multiple WHEN clauses but no ELSE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE deptno"), "Should have CASE with expression");
        assertTrue(normalized.contains("WHEN 10 THEN 'Sales'"), "Should have first WHEN/THEN");
        assertTrue(normalized.contains("WHEN 20 THEN 'Marketing'"), "Should have second WHEN/THEN");
        assertTrue(normalized.contains("WHEN 30 THEN 'IT'"), "Should have third WHEN/THEN");
        assertFalse(normalized.contains("ELSE"), "Should NOT have ELSE clause (no default)");
        assertTrue(normalized.contains("END"), "Should have END keyword");
    }

    @Test
    void decodeInWhereClause() {
        // Given: DECODE in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE DECODE(status, 'A', 1, 0) = 1";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should be transformed in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE"), "Should have WHERE clause");
        assertTrue(normalized.contains("CASE status WHEN 'A' THEN 1 ELSE 0 END"),
            "Should have transformed DECODE in WHERE");
        assertTrue(normalized.contains("= 1"), "Should preserve comparison");
    }

    @Test
    void decodeWithColumnReferences() {
        // Given: DECODE with column references
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(commission, 0, salary, salary + commission) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should handle column references
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE commission"), "Should have CASE with column");
        assertTrue(normalized.contains("WHEN 0"), "Should have WHEN with value");
        assertTrue(normalized.contains("THEN salary"), "Should have THEN with column");
        assertTrue(normalized.contains("ELSE salary + commission"), "Should have ELSE with expression");
    }

    @Test
    void nestedDecode() {
        // Given: Nested DECODE calls
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(DECODE(status, 'A', 'X'), 'X', 'Yes', 'No') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both DECODE calls should be transformed to nested CASE expressions
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Count CASE occurrences (should be 2 for nested DECODE)
        int caseCount = normalized.split("CASE ", -1).length - 1;
        assertEquals(2, caseCount, "Nested DECODE should produce 2 CASE expressions");

        // Inner DECODE: DECODE(status, 'A', 'X')
        assertTrue(normalized.contains("status"), "Should reference status column");

        // Outer DECODE: DECODE(..., 'X', 'Yes', 'No')
        assertTrue(normalized.contains("'Yes'"), "Should have 'Yes' result");
        assertTrue(normalized.contains("'No'"), "Should have 'No' default");
    }

    @Test
    void decodeInOrderByClause() {
        // Given: DECODE in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY DECODE(status, 'A', 1, 'B', 2, 3) DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should be transformed in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY"), "Should have ORDER BY clause");
        assertTrue(normalized.contains("CASE status"), "Should have CASE expression");
        assertTrue(normalized.contains("WHEN 'A' THEN 1"), "Should have first WHEN/THEN");
        assertTrue(normalized.contains("WHEN 'B' THEN 2"), "Should have second WHEN/THEN");
        assertTrue(normalized.contains("ELSE 3"), "Should have ELSE clause");
        assertTrue(normalized.contains("DESC NULLS FIRST"), "Should have DESC with NULLS FIRST");
    }

    @Test
    void decodeWithNullValues() {
        // Given: DECODE with NULL values
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(middle_name, NULL, 'No Middle Name', middle_name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DECODE should handle NULL values
        // Note: Oracle DECODE treats NULL = NULL as true, but CASE WHEN does not
        // This is a semantic difference that users should be aware of
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE middle_name"), "Should have CASE with column");
        assertTrue(normalized.contains("WHEN NULL"), "Should have WHEN with NULL");
        assertTrue(normalized.contains("THEN 'No Middle Name'"), "Should have THEN clause");
        assertTrue(normalized.contains("ELSE middle_name"), "Should have ELSE with column");
    }

    // ==================== Built-in Function Schema Qualification Tests ====================

    @Test
    void coalesceNotSchemaQualified() {
        // Given: Direct COALESCE usage (valid in Oracle)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT COALESCE(commission, bonus, 0) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: COALESCE should NOT be schema-qualified (it's a built-in function)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE( commission , bonus , 0 )"),
            "COALESCE should remain unqualified");
        assertFalse(normalized.contains("hr.coalesce"), "COALESCE should NOT be schema-qualified");
        assertFalse(normalized.contains("HR.COALESCE"), "COALESCE should NOT be schema-qualified (uppercase)");
    }

    @Test
    void upperLowerNotSchemaQualified() {
        // Given: UPPER and LOWER functions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT UPPER(first_name), LOWER(last_name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: UPPER and LOWER should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("UPPER(") || normalized.contains("upper("),
            "UPPER should be present");
        assertTrue(normalized.contains("LOWER(") || normalized.contains("lower("),
            "LOWER should be present");
        assertFalse(normalized.contains("hr.upper") && !normalized.contains("hr.lower"),
            "String functions should NOT be schema-qualified");
    }

    @Test
    void lengthNotSchemaQualified() {
        // Given: LENGTH function
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LENGTH(first_name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LENGTH should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LENGTH(") || normalized.contains("length("),
            "LENGTH should be present");
        assertFalse(normalized.contains("hr.length"),
            "LENGTH should NOT be schema-qualified");
    }

    @Test
    void nullifNotSchemaQualified() {
        // Given: NULLIF function
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NULLIF(first_name, 'Unknown') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NULLIF should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("NULLIF(") || normalized.contains("nullif("),
            "NULLIF should be present");
        assertFalse(normalized.contains("hr.nullif"),
            "NULLIF should NOT be schema-qualified");
    }

    @Test
    void greatestLeastNotSchemaQualified() {
        // Given: GREATEST and LEAST functions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT GREATEST(10, 20, 30), LEAST(10, 20, 30) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: GREATEST and LEAST should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("GREATEST(") || normalized.contains("greatest("),
            "GREATEST should be present");
        assertTrue(normalized.contains("LEAST(") || normalized.contains("least("),
            "LEAST should be present");
        assertFalse(normalized.contains("hr.greatest") && !normalized.contains("hr.least"),
            "GREATEST/LEAST should NOT be schema-qualified");
    }

    @Test
    void mathFunctionsNotSchemaQualified() {
        // Given: Math functions (ABS, CEIL, FLOOR, ROUND)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ABS(salary), CEIL(commission), FLOOR(bonus) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Math functions should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ABS(") || normalized.contains("abs("),
            "ABS should be present");
        assertTrue(normalized.contains("CEIL(") || normalized.contains("ceil("),
            "CEIL should be present");
        assertTrue(normalized.contains("FLOOR(") || normalized.contains("floor("),
            "FLOOR should be present");
        assertFalse(normalized.contains("hr.abs"),
            "Math functions should NOT be schema-qualified");
    }

    @Test
    void aggregateFunctionsNotSchemaQualified() {
        // Given: Aggregate functions (COUNT, SUM, AVG, MIN, MAX)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT COUNT(*), SUM(salary), AVG(commission), MIN(hire_date), MAX(hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Aggregate functions should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT(") || normalized.contains("count("),
            "COUNT should be present");
        assertTrue(normalized.contains("SUM(") || normalized.contains("sum("),
            "SUM should be present");
        assertTrue(normalized.contains("AVG(") || normalized.contains("avg("),
            "AVG should be present");
        assertFalse(normalized.contains("hr.count") && !normalized.contains("hr.sum") && !normalized.contains("hr.avg"),
            "Aggregate functions should NOT be schema-qualified");
    }

    @Test
    void builtinFunctionsInWhereClause() {
        // Given: Built-in functions in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE COALESCE(commission, 0) > 100 AND LENGTH(first_name) > 5";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Built-in functions in WHERE should NOT be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE(") || normalized.contains("coalesce("),
            "COALESCE should be present in WHERE");
        assertTrue(normalized.contains("LENGTH(") || normalized.contains("length("),
            "LENGTH should be present in WHERE");
        assertFalse(normalized.contains("hr.coalesce") && !normalized.contains("hr.length"),
            "Built-in functions in WHERE should NOT be schema-qualified");
    }

    @Test
    void userDefinedFunctionShouldBeSchemaQualified() {
        // Given: A user-defined function (not in built-in list)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT calculate_bonus(salary) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: User-defined function SHOULD be schema-qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("hr.calculate_bonus("),
            "User-defined function should be schema-qualified");
    }

    // ==================== View Column Type Casting Tests ====================

    @Test
    void viewTransformationCastsCountToNumeric() {
        // Given: View transformation with COUNT aggregate (returns bigint) but stub expects numeric
        Map<String, String> viewColumnTypes = new HashMap<>();
        viewColumnTypes.put("cnttest", "numeric");  // Stub column type

        TransformationContext context = new TransformationContext(
            "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices), viewColumnTypes);

        String oracleSql = "SELECT (SELECT COUNT(1) FROM testtable) cnttest FROM testtable";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: COUNT should be cast to numeric to match stub
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("::numeric"),
            "COUNT expression should be cast to numeric, got: " + normalized);
        assertTrue(normalized.contains("AS cnttest"),
            "Column alias should be preserved, got: " + normalized);
    }

    @Test
    void viewTransformationCastsMultipleColumns() {
        // Given: View transformation with multiple columns requiring casts
        Map<String, String> viewColumnTypes = new HashMap<>();
        viewColumnTypes.put("id", "numeric");
        viewColumnTypes.put("name", "text");
        viewColumnTypes.put("cnt", "numeric");

        TransformationContext context = new TransformationContext(
            "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices), viewColumnTypes);

        String oracleSql = "SELECT empno id, ename name, COUNT(*) cnt FROM employees GROUP BY empno, ename";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All columns should be cast to match stub types
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        // Count occurrences of ::numeric (should be 2: id and cnt)
        int numericCasts = (normalized.split("::numeric", -1).length - 1);
        assertEquals(2, numericCasts, "Should have 2 numeric casts (id and cnt), got: " + normalized);

        // Count occurrences of ::text (should be 1: name)
        int textCasts = (normalized.split("::text", -1).length - 1);
        assertEquals(1, textCasts, "Should have 1 text cast (name), got: " + normalized);
    }

    @Test
    void nonViewTransformationDoesNotCast() {
        // Given: Regular transformation WITHOUT view column types
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT (SELECT COUNT(1) FROM testtable) cnttest FROM testtable";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: COUNT should NOT be cast (no view column types provided)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("::numeric"),
            "Non-view transformation should NOT cast, got: " + normalized);
        assertTrue(normalized.contains("AS cnttest"),
            "Column alias should be preserved, got: " + normalized);
    }
}
