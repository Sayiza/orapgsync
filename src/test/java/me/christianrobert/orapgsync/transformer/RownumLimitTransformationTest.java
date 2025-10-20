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
 * Tests for ROWNUM → LIMIT transformation (Phase 1: Simple LIMIT optimization).
 *
 * <p>Oracle's ROWNUM pseudo-column is used to limit the number of rows returned.
 * PostgreSQL uses LIMIT clause for the same purpose.
 *
 * <p><b>Transformation strategy:</b>
 * <ul>
 *   <li><b>Simple LIMIT:</b> {@code WHERE ROWNUM <= 10} → {@code LIMIT 10}</li>
 *   <li><b>With AND:</b> {@code WHERE dept = 10 AND ROWNUM <= 5} → {@code WHERE dept = 10 LIMIT 5}</li>
 *   <li><b>With ORDER BY:</b> {@code WHERE ROWNUM <= 10 ORDER BY sal DESC} → {@code ORDER BY sal DESC LIMIT 10}</li>
 * </ul>
 *
 * <p><b>Test coverage:</b>
 * <ul>
 *   <li>Simple ROWNUM <= N</li>
 *   <li>ROWNUM < N (adjusted to N-1)</li>
 *   <li>ROWNUM with AND conditions</li>
 *   <li>ROWNUM with ORDER BY</li>
 *   <li>Case variations (ROWNUM, rownum, Rownum)</li>
 *   <li>Reversed comparisons (10 >= ROWNUM)</li>
 *   <li>FROM DUAL integration</li>
 * </ul>
 *
 * <p><b>Not yet supported (future phases):</b>
 * <ul>
 *   <li>ROWNUM in SELECT list: {@code SELECT ROWNUM, empno FROM employees}</li>
 *   <li>ROWNUM BETWEEN: {@code WHERE ROWNUM BETWEEN 5 AND 10}</li>
 *   <li>ROWNUM with OR: {@code WHERE ROWNUM <= 10 OR status = 'ACTIVE'}</li>
 * </ul>
 */
class RownumLimitTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== SIMPLE ROWNUM PATTERNS ====================

    @Test
    void rownumLessThanOrEqual() {
        // Given: Simple ROWNUM <= N
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE ROWNUM <= 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM removed from WHERE, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("ROWNUM"), "ROWNUM should be removed");
        assertFalse(normalized.contains("WHERE"), "WHERE should be removed (no other conditions)");
        assertTrue(normalized.contains("LIMIT 10"), "Should add LIMIT 10");
        assertTrue(normalized.matches(".*FROM hr\\.employees LIMIT 10"),
                "LIMIT should come after FROM");
    }

    @Test
    void rownumLessThan() {
        // Given: ROWNUM < N (means first N-1 rows)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE ROWNUM < 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LIMIT adjusted to N-1
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LIMIT 9"), "ROWNUM < 10 should become LIMIT 9");
    }

    @Test
    void rownumWithLargerValue() {
        // Given: ROWNUM with larger value
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE ROWNUM <= 100";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LIMIT 100
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LIMIT 100"), "Should add LIMIT 100");
    }

    // ==================== ROWNUM WITH AND CONDITIONS ====================

    @Test
    void rownumWithAndCondition() {
        // Given: ROWNUM with AND condition (dept = 10 AND ROWNUM <= 5)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE dept_id = 10 AND ROWNUM <= 5";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: dept_id condition kept, ROWNUM removed, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE dept_id = 10"), "dept_id condition should remain");
        assertFalse(normalized.contains("ROWNUM"), "ROWNUM should be removed from WHERE");
        assertTrue(normalized.contains("LIMIT 5"), "Should add LIMIT 5");
        assertTrue(normalized.matches(".*WHERE dept_id = 10 LIMIT 5"),
                "LIMIT should come after WHERE");
    }

    @Test
    void rownumBeforeAndCondition() {
        // Given: ROWNUM before other condition (ROWNUM <= 5 AND dept = 10)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE ROWNUM <= 5 AND dept_id = 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: dept_id condition kept, ROWNUM removed, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE dept_id = 10"), "dept_id condition should remain");
        assertFalse(normalized.contains("ROWNUM"), "ROWNUM should be removed from WHERE");
        assertTrue(normalized.contains("LIMIT 5"), "Should add LIMIT 5");
    }

    @Test
    void rownumBetweenMultipleConditions() {
        // Given: ROWNUM between multiple conditions
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE status = 'ACTIVE' AND ROWNUM <= 10 AND dept_id = 20";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both non-ROWNUM conditions kept, ROWNUM removed, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("status = 'ACTIVE'"), "status condition should remain");
        assertTrue(normalized.contains("dept_id = 20"), "dept_id condition should remain");
        assertTrue(normalized.contains("AND"), "AND should connect remaining conditions");
        assertFalse(normalized.contains("ROWNUM"), "ROWNUM should be removed from WHERE");
        assertTrue(normalized.contains("LIMIT 10"), "Should add LIMIT 10");
    }

    // ==================== ROWNUM WITH ORDER BY ====================

    @Test
    void rownumWithOrderBy() {
        // Given: ROWNUM with ORDER BY (Oracle top-N pattern)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE ROWNUM <= 10 ORDER BY salary DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ORDER BY preserved, LIMIT comes after ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY salary DESC NULLS FIRST"),
                "ORDER BY should be preserved with NULLS FIRST");
        assertTrue(normalized.contains("LIMIT 10"), "Should add LIMIT 10");
        assertTrue(normalized.matches(".*ORDER BY .* LIMIT 10"),
                "LIMIT must come after ORDER BY");
    }

    @Test
    void rownumWithAndOrderBy() {
        // Given: ROWNUM with WHERE condition and ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE dept_id = 10 AND ROWNUM <= 5 ORDER BY hire_date";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: WHERE, ORDER BY, and LIMIT all present
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE dept_id = 10"), "WHERE should be preserved");
        assertTrue(normalized.contains("ORDER BY hire_date"), "ORDER BY should be preserved");
        assertTrue(normalized.contains("LIMIT 5"), "Should add LIMIT 5");
        assertTrue(normalized.matches(".*WHERE .* ORDER BY .* LIMIT 5"),
                "Clause order should be WHERE → ORDER BY → LIMIT");
    }

    // ==================== REVERSED COMPARISONS ====================

    @Test
    void reversedComparison_greaterThanOrEqual() {
        // Given: Reversed comparison (10 >= ROWNUM, same as ROWNUM <= 10)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE 10 >= ROWNUM";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LIMIT 10
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LIMIT 10"), "10 >= ROWNUM should become LIMIT 10");
    }

    @Test
    void reversedComparison_greaterThan() {
        // Given: Reversed comparison (10 > ROWNUM, same as ROWNUM < 10)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE 10 > ROWNUM";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LIMIT 9 (10 > ROWNUM means ROWNUM < 10, which is first 9 rows)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LIMIT 9"), "10 > ROWNUM should become LIMIT 9");
    }

    // ==================== CASE VARIATIONS ====================

    @Test
    void rownumLowercase() {
        // Given: lowercase rownum
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE rownum <= 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LIMIT 10
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LIMIT 10"), "lowercase rownum should work");
    }

    @Test
    void rownumMixedCase() {
        // Given: mixed case RowNum
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE RowNum <= 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LIMIT 10
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("LIMIT 10"), "mixed case RowNum should work");
    }

    // ==================== FROM DUAL INTEGRATION ====================

    @Test
    void rownumWithFromDual() {
        // Given: ROWNUM with FROM DUAL (rare but possible)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT 1 + 1 FROM DUAL WHERE ROWNUM <= 1";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("DUAL"), "FROM DUAL should be removed");
        assertFalse(normalized.contains("WHERE"), "WHERE should be removed");
        assertTrue(normalized.contains("LIMIT 1"), "Should add LIMIT 1");
    }

    // ==================== SELECT STAR INTEGRATION ====================

    @Test
    void rownumWithSelectStar() {
        // Given: ROWNUM with SELECT *
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT * FROM employees WHERE ROWNUM <= 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT * preserved, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.startsWith("SELECT *"), "SELECT * should be preserved");
        assertTrue(normalized.contains("LIMIT 10"), "Should add LIMIT 10");
    }

    // ==================== COMPLEX WHERE CONDITIONS ====================

    @Test
    void rownumWithComplexWhereCondition() {
        // Given: ROWNUM with complex WHERE (multiple ANDs and comparisons)
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE salary > 50000 AND dept_id IN (10, 20) AND ROWNUM <= 10 AND status = 'ACTIVE'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println(oracleSql);
        System.out.println(postgresSql);
        // Then: All non-ROWNUM conditions kept, LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        //System.out.println("DEBUG: " + normalized);  // TODO: Investigate why IN clause missing
        assertTrue(normalized.contains("salary > 50000"), "salary condition should remain");
        assertTrue(normalized.contains("dept_id IN (10, 20)"), "dept_id IN condition should remain");
        assertTrue(normalized.contains("status = 'ACTIVE'"), "status condition should remain");
        assertFalse(normalized.contains("ROWNUM"), "ROWNUM should be removed from WHERE");
        assertTrue(normalized.contains("LIMIT 10"), "Should add LIMIT 10");

    }

    // ==================== NO ROWNUM (REGRESSION TESTS) ====================

    @Test
    void noRownumShouldNotAddLimit() {
        // Given: Query without ROWNUM
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees WHERE dept_id = 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: No LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("LIMIT"), "Should not add LIMIT without ROWNUM");
        assertTrue(normalized.contains("WHERE dept_id = 10"), "WHERE should be preserved");
    }

    @Test
    void noWhereClauseShouldNotAddLimit() {
        // Given: Query without WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);
        String oracleSql = "SELECT empno FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: No LIMIT added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("LIMIT"), "Should not add LIMIT without ROWNUM");
        assertFalse(normalized.contains("WHERE"), "Should not add WHERE");
    }
}
