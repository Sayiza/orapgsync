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
}
