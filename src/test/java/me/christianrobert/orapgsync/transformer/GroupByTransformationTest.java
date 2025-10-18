package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GROUP BY and HAVING clause transformation.
 *
 * <p>Key insight: Oracle and PostgreSQL have nearly identical GROUP BY syntax.
 * The main difference is PostgreSQL's strict enforcement that every non-aggregated
 * column in SELECT must appear in GROUP BY.
 *
 * <p>Strategy: Pass through GROUP BY and HAVING as-is, let PostgreSQL validate.
 * Existing Oracle views that work are likely already compliant.
 *
 * <p>Transformations needed:
 * <ul>
 *   <li>Basic GROUP BY: Pass through (same syntax)</li>
 *   <li>HAVING: Pass through (same syntax)</li>
 *   <li>Aggregates: COUNT, SUM, AVG, MIN, MAX (same syntax, already handled by function visitor)</li>
 *   <li>LISTAGG: Transform to STRING_AGG (future/Day 2)</li>
 * </ul>
 */
class GroupByTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== BASIC GROUP BY TESTS ==========

    @Test
    void groupBySingleColumn() {
        // Given: Basic GROUP BY single column
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: GROUP BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT dept_id , COUNT( * ) FROM hr.employees GROUP BY dept_id", normalized,
                "Basic GROUP BY should be preserved");
    }

    @Test
    void groupByMultipleColumns() {
        // Given: GROUP BY multiple columns
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, job_id, COUNT(*) FROM employees GROUP BY dept_id, job_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Multiple columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT dept_id , job_id , COUNT( * ) FROM hr.employees GROUP BY dept_id , job_id", normalized,
                "GROUP BY with multiple columns should be preserved");
    }

    @Test
    void groupByThreeColumns() {
        // Given: GROUP BY three columns
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, job_id, location_id, COUNT(*) FROM employees GROUP BY dept_id, job_id, location_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("GROUP BY dept_id , job_id , location_id"),
                "GROUP BY with three columns should be preserved");
    }

    // ========== POSITION-BASED GROUP BY ==========

    @Test
    void groupByPosition() {
        // Given: GROUP BY using column position
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY 1";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Position-based GROUP BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT dept_id , COUNT( * ) FROM hr.employees GROUP BY 1", normalized,
                "Position-based GROUP BY should be preserved");
    }

    @Test
    void groupByMultiplePositions() {
        // Given: GROUP BY multiple positions
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, job_id, COUNT(*) FROM employees GROUP BY 1, 2";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Multiple positions preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT dept_id , job_id , COUNT( * ) FROM hr.employees GROUP BY 1 , 2", normalized,
                "GROUP BY with multiple positions should be preserved");
    }

    // ========== AGGREGATE FUNCTIONS ==========

    @Test
    void groupByWithCount() {
        // Given: GROUP BY with COUNT(*)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: COUNT(*) preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT( * )"), "COUNT(*) should be preserved");
    }

    @Test
    void groupByWithCountColumn() {
        // Given: GROUP BY with COUNT(column)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(empno) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: COUNT(column) preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT( empno )"), "COUNT(column) should be preserved");
    }

    @Test
    void groupByWithSum() {
        // Given: GROUP BY with SUM
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, SUM(salary) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SUM preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUM( salary )"), "SUM should be preserved");
    }

    @Test
    void groupByWithAvg() {
        // Given: GROUP BY with AVG
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, AVG(salary) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: AVG preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("AVG( salary )"), "AVG should be preserved");
    }

    @Test
    void groupByWithMinMax() {
        // Given: GROUP BY with MIN and MAX
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, MIN(salary), MAX(salary) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MIN and MAX preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("MIN( salary )"), "MIN should be preserved");
        assertTrue(normalized.contains("MAX( salary )"), "MAX should be preserved");
    }

    @Test
    void groupByWithMultipleAggregates() {
        // Given: GROUP BY with multiple aggregates
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*), SUM(salary), AVG(salary), MIN(salary), MAX(salary) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All aggregates preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT( * )"), "COUNT should be preserved");
        assertTrue(normalized.contains("SUM( salary )"), "SUM should be preserved");
        assertTrue(normalized.contains("AVG( salary )"), "AVG should be preserved");
        assertTrue(normalized.contains("MIN( salary )"), "MIN should be preserved");
        assertTrue(normalized.contains("MAX( salary )"), "MAX should be preserved");
    }

    // ========== HAVING CLAUSE ==========

    @Test
    void groupByWithHaving() {
        // Given: GROUP BY with HAVING clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id HAVING COUNT(*) > 5";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: HAVING clause preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("HAVING COUNT( * ) > 5"), "HAVING clause should be preserved");
    }

    @Test
    void groupByWithHavingMultipleConditions() {
        // Given: HAVING with multiple conditions
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*), AVG(salary) FROM employees GROUP BY dept_id HAVING COUNT(*) > 5 AND AVG(salary) > 50000";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex HAVING preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("HAVING COUNT( * ) > 5 AND AVG( salary ) > 50000"),
                "Complex HAVING clause should be preserved");
    }

    @Test
    void groupByWithHavingOr() {
        // Given: HAVING with OR
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id HAVING COUNT(*) > 10 OR COUNT(*) < 2";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: HAVING with OR preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("HAVING COUNT( * ) > 10 OR COUNT( * ) < 2"),
                "HAVING with OR should be preserved");
    }

    // ========== COMBINED WITH WHERE AND ORDER BY ==========

    @Test
    void groupByWithWhereClause() {
        // Given: GROUP BY with WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees WHERE status = 'ACTIVE' GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both WHERE and GROUP BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE status = 'ACTIVE'"), "WHERE clause should be present");
        assertTrue(normalized.contains("GROUP BY dept_id"), "GROUP BY should be present");
    }

    @Test
    void groupByWithOrderBy() {
        // Given: GROUP BY with ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id ORDER BY COUNT(*) DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both GROUP BY and ORDER BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("GROUP BY dept_id"), "GROUP BY should be present");
        assertTrue(normalized.contains("ORDER BY COUNT( * ) DESC NULLS FIRST"),
                "ORDER BY should be present with NULLS FIRST");
    }

    @Test
    void groupByWithWhereHavingOrderBy() {
        // Given: Complete query with WHERE, GROUP BY, HAVING, ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT dept_id, COUNT(*) " +
                          "FROM employees " +
                          "WHERE status = 'ACTIVE' " +
                          "GROUP BY dept_id " +
                          "HAVING COUNT(*) > 5 " +
                          "ORDER BY COUNT(*) DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All clauses preserved in correct order
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Verify presence of all clauses
        assertTrue(normalized.contains("WHERE status = 'ACTIVE'"), "WHERE should be present");
        assertTrue(normalized.contains("GROUP BY dept_id"), "GROUP BY should be present");
        assertTrue(normalized.contains("HAVING COUNT( * ) > 5"), "HAVING should be present");
        assertTrue(normalized.contains("ORDER BY COUNT( * ) DESC NULLS FIRST"), "ORDER BY should be present");

        // Verify correct order: WHERE before GROUP BY before HAVING before ORDER BY
        int wherePos = normalized.indexOf("WHERE");
        int groupByPos = normalized.indexOf("GROUP BY");
        int havingPos = normalized.indexOf("HAVING");
        int orderByPos = normalized.indexOf("ORDER BY");

        assertTrue(wherePos < groupByPos, "WHERE should come before GROUP BY");
        assertTrue(groupByPos < havingPos, "GROUP BY should come before HAVING");
        assertTrue(havingPos < orderByPos, "HAVING should come before ORDER BY");
    }

    // ========== EDGE CASES ==========

    @Test
    void groupByWithoutContext() {
        // Given: GROUP BY without transformation context
        String oracleSql = "SELECT dept_id, COUNT(*) FROM employees GROUP BY dept_id";

        // When: Parse and transform without context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: GROUP BY works without context (no schema qualification)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT dept_id , COUNT( * ) FROM employees GROUP BY dept_id", normalized,
                "GROUP BY should work without context");
    }

    @Test
    void groupByUppercase() {
        // Given: GROUP BY with uppercase
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT DEPT_ID, COUNT(*) FROM EMPLOYEES GROUP BY DEPT_ID";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Case preserved in columns, table qualified with lowercase schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("DEPT_ID"), "Column case should be preserved");
        assertTrue(normalized.contains("hr.employees"), "Table should be lowercase qualified");
        assertTrue(normalized.contains("GROUP BY DEPT_ID"), "GROUP BY case should be preserved");
    }

    @Test
    void groupByWithAlias() {
        // Given: GROUP BY with table alias
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT e.dept_id, COUNT(*) FROM employees e GROUP BY e.dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Alias preserved in GROUP BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("GROUP BY e . dept_id"), "Qualified column in GROUP BY should be preserved");
    }
}
