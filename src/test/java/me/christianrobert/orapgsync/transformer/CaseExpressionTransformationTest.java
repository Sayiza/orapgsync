package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CASE expression transformations.
 *
 * <p>Oracle and PostgreSQL have nearly identical CASE expression syntax!
 * The main difference is that Oracle allows "END CASE" while PostgreSQL only allows "END".
 *
 * <p>Two types of CASE expressions:
 * <ul>
 *   <li>Searched CASE: CASE WHEN condition THEN result ... ELSE default END</li>
 *   <li>Simple CASE: CASE expr WHEN value THEN result ... ELSE default END</li>
 * </ul>
 *
 * <p>Note: DECODE is transformed to simple CASE format (tested in OracleFunctionTransformationTest).
 * This test class focuses on direct CASE expression usage.
 */
public class CaseExpressionTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== Searched CASE Expressions ====================

    @Test
    void searchedCaseSimpleTwoWhen() {
        // Given: Searched CASE with two WHEN clauses
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN sal > 5000 THEN 'High' WHEN sal > 2000 THEN 'Medium' ELSE 'Low' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE expression should be preserved with END (not END CASE)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE WHEN sal > 5000 THEN 'High'"),
            "Should have first WHEN clause");
        assertTrue(normalized.contains("WHEN sal > 2000 THEN 'Medium'"),
            "Should have second WHEN clause");
        assertTrue(normalized.contains("ELSE 'Low' END"),
            "Should have ELSE clause with END (not END CASE)");
        assertFalse(normalized.contains("END CASE"),
            "Should not contain 'END CASE' (PostgreSQL requires just 'END')");
    }

    @Test
    void searchedCaseWithoutElse() {
        // Given: Searched CASE without ELSE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN status = 'A' THEN 'Active' WHEN status = 'I' THEN 'Inactive' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE expression should work without ELSE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE WHEN status = 'A' THEN 'Active'"),
            "Should have first WHEN clause");
        assertTrue(normalized.contains("WHEN status = 'I' THEN 'Inactive' END"),
            "Should have second WHEN clause ending with END");
        assertFalse(normalized.contains("ELSE"),
            "Should not have ELSE clause");
    }

    @Test
    void searchedCaseWithColumnAlias() {
        // Given: Searched CASE with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, CASE WHEN sal > 5000 THEN 'High' ELSE 'Normal' END AS sal_category FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE expression should have alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("END AS sal_category"),
            "CASE expression should have alias");
    }

    @Test
    void searchedCaseInWhereClause() {
        // Given: Searched CASE in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE CASE WHEN deptno = 10 THEN 1 ELSE 0 END = 1";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE expression should work in WHERE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE CASE WHEN deptno = 10 THEN 1 ELSE 0 END = 1"),
            "CASE should work in WHERE clause");
    }

    @Test
    void searchedCaseWithComplexConditions() {
        // Given: Searched CASE with complex AND/OR conditions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN sal > 5000 AND deptno = 10 THEN 'High Sales' " +
                          "WHEN sal > 3000 OR commission > 1000 THEN 'Good' ELSE 'Normal' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex conditions should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("sal > 5000 AND deptno = 10"),
            "Should preserve AND condition");
        assertTrue(normalized.contains("sal > 3000 OR commission > 1000"),
            "Should preserve OR condition");
    }

    @Test
    void searchedCaseNested() {
        // Given: Nested CASE expressions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN deptno = 10 THEN " +
                          "CASE WHEN sal > 5000 THEN 'High' ELSE 'Normal' END " +
                          "ELSE 'Other' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Nested CASE should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        int caseCount = normalized.split("CASE ", -1).length - 1;
        assertEquals(2, caseCount, "Should have 2 CASE expressions (nested)");
        int endCount = normalized.split(" END", -1).length - 1;
        assertEquals(2, endCount, "Should have 2 END keywords");
    }

    @Test
    void searchedCaseWithNullCheck() {
        // Given: Searched CASE with IS NULL check
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN commission IS NULL THEN 0 ELSE commission END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: IS NULL should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHEN commission IS NULL THEN 0"),
            "Should preserve IS NULL check");
    }

    @Test
    void searchedCaseInOrderBy() {
        // Given: Searched CASE in ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY CASE WHEN deptno = 10 THEN 1 ELSE 2 END";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE should work in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY CASE WHEN deptno = 10 THEN 1 ELSE 2 END"),
            "CASE should work in ORDER BY");
    }

    // ==================== Simple CASE Expressions ====================

    @Test
    void simpleCaseTwoWhen() {
        // Given: Simple CASE with two WHEN clauses
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE deptno WHEN 10 THEN 'Sales' WHEN 20 THEN 'IT' ELSE 'Other' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Simple CASE should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE deptno WHEN 10 THEN 'Sales'"),
            "Should have selector expression");
        assertTrue(normalized.contains("WHEN 20 THEN 'IT'"),
            "Should have second WHEN");
        assertTrue(normalized.contains("ELSE 'Other' END"),
            "Should have ELSE with END");
    }

    @Test
    void simpleCaseWithoutElse() {
        // Given: Simple CASE without ELSE
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE status WHEN 'A' THEN 'Active' WHEN 'I' THEN 'Inactive' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Simple CASE without ELSE should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE status"),
            "Should have selector");
        assertFalse(normalized.contains("ELSE"),
            "Should not have ELSE");
        assertTrue(normalized.contains("END FROM"),
            "Should end with END");
    }

    @Test
    void simpleCaseWithNumericValues() {
        // Given: Simple CASE with numeric selector and values
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE deptno WHEN 10 THEN 100 WHEN 20 THEN 200 ELSE 0 END AS bonus FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric values should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHEN 10 THEN 100"),
            "Should have numeric WHEN/THEN");
        assertTrue(normalized.contains("WHEN 20 THEN 200"),
            "Should have second numeric WHEN/THEN");
        assertTrue(normalized.contains("END AS bonus"),
            "Should have alias");
    }

    @Test
    void simpleCaseWithExpression() {
        // Given: Simple CASE with expression as selector
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE salary * 12 WHEN 60000 THEN 'Target' ELSE 'Other' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Expression selector should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE salary * 12 WHEN 60000"),
            "Should have expression as selector");
    }

    // ==================== CASE with Other Features ====================

    @Test
    void caseWithArithmetic() {
        // Given: CASE with arithmetic in THEN clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN deptno = 10 THEN salary * 1.1 ELSE salary END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Arithmetic should work in THEN
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("THEN salary * 1.1"),
            "Should have arithmetic in THEN");
    }

    @Test
    void caseWithFunctionCall() {
        // Given: CASE with function call in condition and result
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN NVL(commission, 0) > 1000 THEN 'High' ELSE 'Low' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Function call should work (NVL → COALESCE)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHEN COALESCE( commission , 0 ) > 1000"),
            "Should transform NVL to COALESCE within CASE");
    }

    @Test
    void caseWithBetween() {
        // Given: CASE with BETWEEN in condition
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN sal BETWEEN 2000 AND 5000 THEN 'Medium' ELSE 'Other' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: BETWEEN should work in CASE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHEN sal BETWEEN 2000 AND 5000"),
            "Should preserve BETWEEN operator");
    }

    @Test
    void caseWithIn() {
        // Given: CASE with IN operator in condition
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT CASE WHEN deptno IN (10, 20, 30) THEN 'Core' ELSE 'Other' END FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: IN operator should work in CASE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHEN deptno IN"),
            "Should preserve IN operator");
        assertTrue(normalized.contains("10") && normalized.contains("20") && normalized.contains("30"),
            "Should have IN values");
    }

    @Test
    void multipleCaseExpressionsInSelect() {
        // Given: Multiple CASE expressions in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT " +
                          "CASE WHEN sal > 5000 THEN 'High' ELSE 'Low' END AS sal_level, " +
                          "CASE deptno WHEN 10 THEN 'Sales' ELSE 'Other' END AS dept_name " +
                          "FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both CASE expressions should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        int caseCount = normalized.split("CASE ", -1).length - 1;
        assertEquals(2, caseCount, "Should have 2 CASE expressions");
        assertTrue(normalized.contains("AS sal_level"),
            "Should have first alias");
        assertTrue(normalized.contains("AS dept_name"),
            "Should have second alias");
    }
}
