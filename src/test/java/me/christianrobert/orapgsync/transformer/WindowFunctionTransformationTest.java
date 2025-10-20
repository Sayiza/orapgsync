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
 * Tests for window function (OVER clause) transformation.
 *
 * <p>Window functions in Oracle and PostgreSQL have nearly identical syntax,
 * so transformation is mostly pass-through. Tests cover:
 * - Basic window functions (ROW_NUMBER, RANK, DENSE_RANK)
 * - PARTITION BY clause
 * - ORDER BY within OVER
 * - Window frames (ROWS/RANGE BETWEEN ... AND ...)
 * - Aggregate functions with OVER
 * - Analytic functions (LEAD, LAG, FIRST_VALUE, LAST_VALUE)
 * - Empty OVER clause
 * - Complex scenarios
 */
class WindowFunctionTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== BASIC WINDOW FUNCTIONS ==========

    @Test
    void testRowNumber_withOrderBy() {
        // Given: ROW_NUMBER window function with ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT ROW_NUMBER() OVER (ORDER BY salary DESC) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: OVER clause preserved (identical syntax)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ROW_NUMBER(") && normalized.contains(") OVER ("),
                "Should preserve ROW_NUMBER with OVER");
        assertTrue(normalized.contains("ORDER BY salary DESC"),
                "Should preserve ORDER BY");
    }

    @Test
    void testRowNumber_withPartitionAndOrder() {
        // Given: ROW_NUMBER with PARTITION BY and ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT ROW_NUMBER() OVER (PARTITION BY department_id ORDER BY salary DESC) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Full OVER clause preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("PARTITION BY department_id"),
                "Should preserve PARTITION BY");
        assertTrue(normalized.contains("ORDER BY salary DESC"),
                "Should preserve ORDER BY");
    }

    @Test
    void testRank_withPartitionAndOrder() {
        // Given: RANK window function
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT RANK() OVER (PARTITION BY department_id ORDER BY salary DESC) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RANK preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("RANK(") && normalized.contains(") OVER ("),
                "Should preserve RANK");
    }

    @Test
    void testDenseRank_withPartitionAndOrder() {
        // Given: DENSE_RANK window function
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT DENSE_RANK() OVER (PARTITION BY department_id ORDER BY salary DESC) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DENSE_RANK preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("DENSE_RANK(") && normalized.contains(") OVER ("),
                "Should preserve DENSE_RANK");
    }

    // ========== PARTITION BY VARIATIONS ==========

    @Test
    void testPartitionBy_singleColumn() {
        // Given: PARTITION BY with single column
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT COUNT(*) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: PARTITION BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("PARTITION BY department_id"),
                "Should preserve PARTITION BY");
    }

    @Test
    void testPartitionBy_multipleColumns() {
        // Given: PARTITION BY with multiple columns
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT COUNT(*) OVER (PARTITION BY department_id, job_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Multiple columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("PARTITION BY department_id , job_id"),
                "Should preserve multiple PARTITION BY columns");
    }

    @Test
    void testPartitionBy_withTableAlias() {
        // Given: PARTITION BY with qualified column
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT COUNT(*) OVER (PARTITION BY e.department_id) FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified column preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("e . department_id"),
                "Should preserve qualified column");
    }

    // ========== WINDOW FRAMES (ROWS/RANGE) ==========

    @Test
    void testWindowFrame_rowsUnboundedPreceding() {
        // Given: ROWS UNBOUNDED PRECEDING
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER (ORDER BY hire_date ROWS UNBOUNDED PRECEDING) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Window frame preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ROWS UNBOUNDED PRECEDING"),
                "Should preserve ROWS UNBOUNDED PRECEDING");
    }

    @Test
    void testWindowFrame_rowsBetweenUnboundedAndCurrent() {
        // Given: ROWS BETWEEN ... AND CURRENT ROW
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER (ORDER BY hire_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: BETWEEN frame preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"),
                "Should preserve ROWS BETWEEN");
    }

    @Test
    void testWindowFrame_rowsBetweenPrecedingAndFollowing() {
        // Given: ROWS BETWEEN N PRECEDING AND N FOLLOWING
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT AVG(salary) OVER (ORDER BY hire_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric bounds preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("1 PRECEDING AND 1 FOLLOWING"),
                "Should preserve numeric bounds");
    }

    @Test
    void testWindowFrame_rangeUnboundedPreceding() {
        // Given: RANGE UNBOUNDED PRECEDING
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER (ORDER BY hire_date RANGE UNBOUNDED PRECEDING) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RANGE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("RANGE UNBOUNDED PRECEDING"),
                "Should preserve RANGE UNBOUNDED PRECEDING");
    }

    @Test
    void testWindowFrame_rangeBetweenUnboundedAndCurrent() {
        // Given: RANGE BETWEEN
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER (ORDER BY hire_date RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RANGE BETWEEN preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"),
                "Should preserve RANGE BETWEEN");
    }

    // ========== AGGREGATE FUNCTIONS WITH OVER ==========

    @Test
    void testCount_withOver() {
        // Given: COUNT with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT COUNT(*) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: COUNT with OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT( * ) OVER"),
                "Should preserve COUNT with OVER");
    }

    @Test
    void testSum_withOver() {
        // Given: SUM with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SUM with OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUM( salary ) OVER"),
                "Should preserve SUM with OVER");
    }

    @Test
    void testAvg_withOver() {
        // Given: AVG with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT AVG(salary) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: AVG with OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("AVG( salary ) OVER"),
                "Should preserve AVG with OVER");
    }

    @Test
    void testMin_withOver() {
        // Given: MIN with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT MIN(salary) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MIN with OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("MIN( salary ) OVER"),
                "Should preserve MIN with OVER");
    }

    @Test
    void testMax_withOver() {
        // Given: MAX with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT MAX(salary) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MAX with OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("MAX( salary ) OVER"),
                "Should preserve MAX with OVER");
    }

    // ========== ANALYTIC FUNCTIONS ==========

    @Test
    void testLead_basic() {
        // Given: LEAD function
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT LEAD(salary) OVER (ORDER BY hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LEAD preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LEAD( salary )"),
                "Should preserve LEAD");
    }

    @Test
    void testLead_withOffset() {
        // Given: LEAD with offset
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT LEAD(salary, 2) OVER (ORDER BY hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LEAD with offset preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LEAD( salary , 2 )"),
                "Should preserve LEAD with offset");
    }

    @Test
    void testLag_basic() {
        // Given: LAG function
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT LAG(salary) OVER (ORDER BY hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LAG preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LAG( salary )"),
                "Should preserve LAG");
    }

    @Test
    void testLag_withOffset() {
        // Given: LAG with offset
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT LAG(salary, 1) OVER (ORDER BY hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LAG with offset preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LAG( salary , 1 )"),
                "Should preserve LAG with offset");
    }

    @Test
    void testFirstValue_withFrame() {
        // Given: FIRST_VALUE function
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT FIRST_VALUE(salary) OVER (ORDER BY hire_date ROWS UNBOUNDED PRECEDING) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FIRST_VALUE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("FIRST_VALUE( salary )"),
                "Should preserve FIRST_VALUE");
    }

    @Test
    void testLastValue_withFrame() {
        // Given: LAST_VALUE function (note: requires special frame to work as expected)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT LAST_VALUE(salary) OVER (ORDER BY hire_date ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LAST_VALUE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LAST_VALUE( salary )"),
                "Should preserve LAST_VALUE");
    }

    // ========== EMPTY OVER CLAUSE ==========

    @Test
    void testEmptyOver_count() {
        // Given: COUNT with empty OVER clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT COUNT(*) OVER () FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Empty OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("OVER ( )"),
                "Should preserve empty OVER clause");
    }

    @Test
    void testEmptyOver_sum() {
        // Given: SUM with empty OVER clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER () FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Empty OVER preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("OVER ( )"),
                "Should preserve empty OVER clause");
    }

    // ========== COMPLEX SCENARIOS ==========

    @Test
    void testMultipleWindowFunctions_sameQuery() {
        // Given: Multiple window functions in one query
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT ROW_NUMBER() OVER (ORDER BY salary DESC), RANK() OVER (ORDER BY salary DESC) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ROW_NUMBER("),
                "Should preserve ROW_NUMBER");
        assertTrue(normalized.contains("RANK("),
                "Should preserve RANK");
    }

    @Test
    void testWindowFunction_withWhereClause() {
        // Given: Window function with WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT ROW_NUMBER() OVER (ORDER BY salary DESC) FROM employees WHERE department_id = 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: WHERE clause preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE department_id = 10"),
                "Should preserve WHERE clause");
    }

    @Test
    void testWindowFunction_fromDual() {
        // Given: Window function with FROM DUAL
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT ROW_NUMBER() OVER (ORDER BY 1) FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("DUAL"),
                "Should remove FROM DUAL");
    }

    // ========== WINDOW FUNCTIONS WITH DISTINCT ==========

    @Test
    void testCountDistinct_withOver() {
        // Given: COUNT DISTINCT with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT COUNT(DISTINCT job_id) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DISTINCT preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT( DISTINCT job_id )"),
                "Should preserve COUNT DISTINCT");
    }

    // Note: SUM DISTINCT with window functions is not yet supported
    // Uncomment when DISTINCT support is added
    /*
    @Test
    void testSumDistinct_withOver() {
        // Given: SUM DISTINCT with OVER
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(DISTINCT salary) OVER (PARTITION BY department_id) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DISTINCT preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUM( DISTINCT salary )"),
                "Should preserve SUM DISTINCT");
    }
    */

    // ========== FULL WINDOW SPECIFICATION ==========

    @Test
    void testFullWindowSpec_partitionOrderFrame() {
        // Given: Full window spec with partition, order, and frame
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT SUM(salary) OVER (PARTITION BY department_id ORDER BY hire_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All components preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("PARTITION BY department_id"),
                "Should preserve PARTITION BY");
        assertTrue(normalized.contains("ORDER BY hire_date"),
                "Should preserve ORDER BY");
        assertTrue(normalized.contains("ROWS BETWEEN"),
                "Should preserve ROWS BETWEEN");
    }

    @Test
    void testFullWindowSpec_multiplePartitionColumns() {
        // Given: Multiple partition columns with order and frame
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT AVG(salary) OVER (PARTITION BY department_id, job_id ORDER BY hire_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Multiple partition columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("PARTITION BY department_id , job_id"),
                "Should preserve multiple PARTITION BY columns");
    }
}
