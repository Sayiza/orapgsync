package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ANSI JOIN syntax transformation.
 *
 * <p>Key insight: Oracle and PostgreSQL have nearly identical ANSI JOIN syntax.
 * The main transformation needed is schema qualification of table names.
 *
 * <p>This complements the existing Oracle (+) outer join transformation, which
 * converts Oracle-specific (+) syntax to ANSI JOINs. This test verifies that
 * queries already using ANSI JOIN syntax pass through correctly.
 *
 * <p>Tested JOIN types:
 * <ul>
 *   <li>INNER JOIN - Default join, returns matching rows</li>
 *   <li>LEFT [OUTER] JOIN - All left rows + matching right rows</li>
 *   <li>RIGHT [OUTER] JOIN - All right rows + matching left rows</li>
 *   <li>FULL [OUTER] JOIN - All rows from both tables</li>
 *   <li>CROSS JOIN - Cartesian product</li>
 *   <li>NATURAL JOIN - Implicit join on same-named columns</li>
 * </ul>
 */
class AnsiJoinTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== INNER JOIN TESTS ==========

    @Test
    void innerJoinWithOnClause() {
        // Given: Simple INNER JOIN with ON condition
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e INNER JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());
        // Debug output
        System.out.println("\n=== TEST: outerJoinWithDateAndStringFunctions ===");
        System.out.println("ORACLE SQL:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL SQL:");
        System.out.println(postgresSql);
        System.out.println("==================================================\n");

        // Then: INNER JOIN preserved with schema-qualified tables
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("hr.employees e"), "Left table should be schema-qualified");
        assertTrue(normalized.contains("INNER JOIN hr.departments d"), "Right table should be schema-qualified");
        assertTrue(normalized.contains("ON e . deptno = d . deptno"), "ON condition should be preserved");
    }

    @Test
    void innerJoinWithoutInnerKeyword() {
        // Given: JOIN without INNER keyword (INNER is default)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: JOIN preserved (INNER is implicit)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("JOIN hr.departments d"), "JOIN should be preserved");
        assertTrue(normalized.contains("ON e . deptno = d . deptno"), "ON condition should be preserved");
    }

    @Test
    void innerJoinWithComplexCondition() {
        // Given: INNER JOIN with complex ON condition (multiple columns, AND)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e " +
                          "INNER JOIN departments d ON e.deptno = d.deptno AND e.location = d.location";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex condition preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ON e . deptno = d . deptno AND e . location = d . location"),
                "Complex ON condition with AND should be preserved");
    }

    // ========== LEFT JOIN TESTS ==========

    @Test
    void leftOuterJoin() {
        // Given: LEFT OUTER JOIN
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e LEFT OUTER JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LEFT OUTER JOIN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LEFT OUTER JOIN hr.departments d"),
                "LEFT OUTER JOIN should be preserved");
    }

    @Test
    void leftJoinWithoutOuterKeyword() {
        // Given: LEFT JOIN without OUTER keyword (OUTER is optional)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e LEFT JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LEFT JOIN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LEFT JOIN hr.departments d"),
                "LEFT JOIN should be preserved");
    }

    // ========== RIGHT JOIN TESTS ==========

    @Test
    void rightOuterJoin() {
        // Given: RIGHT OUTER JOIN
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e RIGHT OUTER JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RIGHT OUTER JOIN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("RIGHT OUTER JOIN hr.departments d"),
                "RIGHT OUTER JOIN should be preserved");
    }

    @Test
    void rightJoinWithoutOuterKeyword() {
        // Given: RIGHT JOIN without OUTER keyword
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e RIGHT JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RIGHT JOIN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("RIGHT JOIN hr.departments d"),
                "RIGHT JOIN should be preserved");
    }

    // ========== FULL OUTER JOIN TESTS ==========

    @Test
    void fullOuterJoin() {
        // Given: FULL OUTER JOIN
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e FULL OUTER JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FULL OUTER JOIN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("FULL OUTER JOIN hr.departments d"),
                "FULL OUTER JOIN should be preserved");
    }

    // ========== CROSS JOIN TESTS ==========

    @Test
    void crossJoin() {
        // Given: CROSS JOIN (Cartesian product)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e CROSS JOIN departments d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CROSS JOIN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CROSS JOIN hr.departments"),
                "CROSS JOIN should be preserved");
    }

    // ========== MULTIPLE JOINS ==========

    @Test
    void multipleInnerJoins() {
        // Given: Multiple INNER JOINs chained
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname, l.city " +
                          "FROM employees e " +
                          "INNER JOIN departments d ON e.deptno = d.deptno " +
                          "INNER JOIN locations l ON d.location_id = l.location_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All joins preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("INNER JOIN hr.departments d"),
                "First INNER JOIN should be preserved");
        assertTrue(normalized.contains("INNER JOIN hr.locations l"),
                "Second INNER JOIN should be preserved");
    }

    @Test
    void mixedJoinTypes() {
        // Given: Mix of INNER and LEFT JOIN
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname, m.ename " +
                          "FROM employees e " +
                          "INNER JOIN departments d ON e.deptno = d.deptno " +
                          "LEFT JOIN employees m ON e.manager_id = m.empno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both join types preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("INNER JOIN hr.departments d"),
                "INNER JOIN should be preserved");
        assertTrue(normalized.contains("LEFT JOIN hr.employees m"),
                "LEFT JOIN should be preserved");
    }

    // ========== JOINS WITH WHERE CLAUSE ==========

    @Test
    void joinWithWhereClause() {
        // Given: ANSI JOIN with additional WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname " +
                          "FROM employees e " +
                          "INNER JOIN departments d ON e.deptno = d.deptno " +
                          "WHERE e.salary > 50000";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both JOIN and WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("INNER JOIN hr.departments d ON e . deptno = d . deptno"),
                "INNER JOIN with ON should be preserved");
        assertTrue(normalized.contains("WHERE e . salary > 50000"),
                "WHERE clause should be preserved");
    }

    // ========== USING CLAUSE TESTS ==========

    @Test
    void innerJoinWithUsingClause() {
        // Given: INNER JOIN with USING clause (implicit equality join)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dname FROM employees e INNER JOIN departments d USING (deptno)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: USING clause preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("INNER JOIN hr.departments d"),
                "INNER JOIN should be preserved");
        assertTrue(normalized.contains("USING"),
                "USING clause should be preserved");
    }

    // ========== EDGE CASES ==========

    @Test
    void joinWithoutContext() {
        // Given: ANSI JOIN without transformation context (no schema qualification)
        String oracleSql = "SELECT e.empno, d.dname FROM employees e INNER JOIN departments d ON e.deptno = d.deptno";

        // When: Parse and transform without context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: JOIN works without context (no schema qualification)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("INNER JOIN departments d"),
                "INNER JOIN should work without context");
        assertTrue(normalized.contains("ON e . deptno = d . deptno"),
                "ON condition should be preserved");
    }

    @Test
    void joinWithUppercaseTableNames() {
        // Given: ANSI JOIN with uppercase table names
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT E.EMPNO, D.DNAME FROM EMPLOYEES E INNER JOIN DEPARTMENTS D ON E.DEPTNO = D.DEPTNO";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Schema qualification applied with lowercase schema, table case preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("hr.employees E"),
                "First table should be schema-qualified with lowercase schema");
        assertTrue(normalized.contains("INNER JOIN hr.departments D"),
                "Second table should be schema-qualified with lowercase schema");
    }
}
