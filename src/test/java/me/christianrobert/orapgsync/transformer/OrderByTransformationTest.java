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
 * Tests for ORDER BY clause transformation.
 *
 * <p>Key difference between Oracle and PostgreSQL:
 * <ul>
 *   <li>Oracle: ORDER BY col DESC → NULLs come FIRST (default)</li>
 *   <li>PostgreSQL: ORDER BY col DESC → NULLs come LAST (default)</li>
 * </ul>
 *
 * <p>Solution: Add explicit NULLS FIRST to DESC columns without explicit NULL ordering:
 * <pre>
 * Oracle:     ORDER BY empno DESC
 * PostgreSQL: ORDER BY empno DESC NULLS FIRST
 * </pre>
 *
 * <p>No transformation needed for:
 * <ul>
 *   <li>ASC (same default in both databases)</li>
 *   <li>Explicit NULLS FIRST/LAST (same syntax in both)</li>
 *   <li>Position-based ordering (same syntax)</li>
 *   <li>Expression ordering (expressions transform separately)</li>
 * </ul>
 */
class OrderByTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== BASIC ORDER BY TESTS ==========

    @Test
    void orderByColumnNoDirection() {
        // Given: ORDER BY without direction (defaults to ASC)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY empno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ORDER BY preserved (defaults to ASC, same behavior in both)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY empno", normalized,
                "ORDER BY without direction should be preserved (defaults to ASC)");
    }

    @Test
    void orderByColumnAscending() {
        // Given: ORDER BY with explicit ASC
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY empno ASC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ASC preserved (same default NULL behavior in both databases)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY empno ASC", normalized,
                "ORDER BY ASC should be preserved");
    }

    @Test
    void orderByColumnDescending() {
        // Given: ORDER BY with DESC (no explicit NULLS clause)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY empno DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DESC with NULLS FIRST added (to match Oracle's default behavior)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY empno DESC NULLS FIRST", normalized,
                "ORDER BY DESC should add NULLS FIRST to match Oracle behavior");
    }

    // ========== EXPLICIT NULL ORDERING (PASS THROUGH) ==========

    @Test
    void orderByDescNullsFirst() {
        // Given: ORDER BY DESC with explicit NULLS FIRST
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY empno DESC NULLS FIRST";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Pass through as-is (explicit NULL ordering)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno FROM hr.employees ORDER BY empno DESC NULLS FIRST", normalized,
                "Explicit NULLS FIRST should be preserved");
    }

    @Test
    void orderByDescNullsLast() {
        // Given: ORDER BY DESC with explicit NULLS LAST
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY empno DESC NULLS LAST";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Pass through as-is (explicit NULL ordering)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno FROM hr.employees ORDER BY empno DESC NULLS LAST", normalized,
                "Explicit NULLS LAST should be preserved");
    }

    @Test
    void orderByAscNullsFirst() {
        // Given: ORDER BY ASC with explicit NULLS FIRST
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY empno ASC NULLS FIRST";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Pass through as-is (explicit NULL ordering)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno FROM hr.employees ORDER BY empno ASC NULLS FIRST", normalized,
                "Explicit NULLS FIRST with ASC should be preserved");
    }

    @Test
    void orderByAscNullsLast() {
        // Given: ORDER BY ASC with explicit NULLS LAST
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY empno ASC NULLS LAST";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Pass through as-is (explicit NULL ordering)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno FROM hr.employees ORDER BY empno ASC NULLS LAST", normalized,
                "Explicit NULLS LAST with ASC should be preserved");
    }

    // ========== MULTIPLE COLUMNS ==========

    @Test
    void orderByMultipleColumnsAscending() {
        // Given: ORDER BY multiple columns ASC
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY empno ASC, ename ASC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both columns preserved (no transformation needed for ASC)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY empno ASC , ename ASC", normalized);
    }

    @Test
    void orderByMultipleColumnsDescending() {
        // Given: ORDER BY multiple columns DESC
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY empno DESC, ename DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both columns get NULLS FIRST
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY empno DESC NULLS FIRST , ename DESC NULLS FIRST", normalized,
                "Both DESC columns should get NULLS FIRST");
    }

    @Test
    void orderByMixedAscDesc() {
        // Given: ORDER BY with mixed ASC and DESC
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY empno DESC, ename ASC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Only DESC column gets NULLS FIRST
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY empno DESC NULLS FIRST , ename ASC", normalized,
                "Only DESC column should get NULLS FIRST");
    }

    @Test
    void orderByThreeColumns() {
        // Given: ORDER BY three columns with different directions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename, sal FROM employees ORDER BY empno DESC, ename ASC, sal DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both DESC columns get NULLS FIRST
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename , sal FROM hr.employees ORDER BY empno DESC NULLS FIRST , ename ASC , sal DESC NULLS FIRST", normalized);
    }

    // ========== POSITION-BASED ORDERING ==========

    @Test
    void orderByPosition() {
        // Given: ORDER BY using column positions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY 1, 2";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Position-based ordering preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY 1 , 2", normalized,
                "Position-based ordering should be preserved");
    }

    @Test
    void orderByPositionWithDirection() {
        // Given: ORDER BY positions with DESC
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY 1 DESC, 2 ASC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DESC position gets NULLS FIRST
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees ORDER BY 1 DESC NULLS FIRST , 2 ASC", normalized,
                "Position-based DESC should get NULLS FIRST");
    }

    // ========== EXPRESSION ORDERING ==========

    @Test
    void orderByExpression() {
        // Given: ORDER BY with function expression
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees ORDER BY UPPER(ename)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Expression preserved (transforms via expression visitor)
        // Note: UPPER() function not yet implemented, but should not crash
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertNotNull(postgresSql);
        assertTrue(normalized.contains("ORDER BY"),
                "ORDER BY clause should be present");
    }

    @Test
    void orderByExpressionDescending() {
        // Given: ORDER BY expression with DESC
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, sal FROM employees ORDER BY sal * 12 DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Expression with DESC gets NULLS FIRST
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        // Note: Arithmetic operators not yet implemented, so this may not work yet
        // For now, just verify it doesn't crash
        assertNotNull(postgresSql);
    }

    @Test
    void orderByAlias() {
        // Given: ORDER BY column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT sal * 12 AS annual_sal FROM employees ORDER BY annual_sal DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Alias ordering with DESC gets NULLS FIRST
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        // Note: Arithmetic operators not yet implemented
        assertNotNull(postgresSql);
    }

    // ========== EDGE CASES ==========

    @Test
    void orderByWithoutContext() {
        // Given: ORDER BY without transformation context
        String oracleSql = "SELECT empno FROM employees ORDER BY empno DESC";

        // When: Parse and transform without context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Still adds NULLS FIRST (not dependent on context)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno FROM employees ORDER BY empno DESC NULLS FIRST", normalized,
                "ORDER BY DESC transformation should work without context");
    }

    @Test
    void orderByUppercase() {
        // Given: ORDER BY with uppercase keywords
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT EMPNO FROM EMPLOYEES ORDER BY EMPNO DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Case preserved in columns, schema qualification lowercases table name, NULLS FIRST added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT EMPNO FROM hr.employees ORDER BY EMPNO DESC NULLS FIRST", normalized,
                "Uppercase columns should be preserved with NULLS FIRST added, schema.table is lowercase");
    }

    @Test
    void orderByWithWhereClause() {
        // Given: ORDER BY with WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees WHERE deptno = 10 ORDER BY empno DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both WHERE and ORDER BY preserved correctly
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM hr.employees WHERE deptno = 10 ORDER BY empno DESC NULLS FIRST", normalized,
                "WHERE clause and ORDER BY should both work correctly");
    }
}
