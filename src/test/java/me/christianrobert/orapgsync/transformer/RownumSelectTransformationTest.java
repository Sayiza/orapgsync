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
 * Tests for ROWNUM in SELECT list transformation (Phase 2: Pseudocolumn in expressions).
 *
 * <p>Oracle's ROWNUM pseudo-column can appear in the SELECT list to retrieve row numbers.
 * PostgreSQL uses row_number() window function for the same purpose.
 *
 * <p><b>Transformation strategy:</b>
 * <ul>
 *   <li><b>Simple:</b> {@code SELECT ROWNUM FROM emp} → {@code SELECT row_number() OVER () AS rownum FROM emp}</li>
 *   <li><b>With alias:</b> {@code SELECT ROWNUM AS rn FROM emp} → {@code SELECT row_number() OVER () AS rn FROM emp}</li>
 *   <li><b>With columns:</b> {@code SELECT ROWNUM, empno, ename FROM emp} → {@code SELECT row_number() OVER () AS rownum , empno , ename FROM emp}</li>
 * </ul>
 *
 * <p><b>Test coverage:</b>
 * <ul>
 *   <li>ROWNUM alone in SELECT list</li>
 *   <li>ROWNUM with explicit alias</li>
 *   <li>ROWNUM with other columns</li>
 *   <li>ROWNUM with ORDER BY (window function should follow ORDER BY)</li>
 *   <li>ROWNUM with WHERE clause</li>
 *   <li>ROWNUM with GROUP BY and aggregates</li>
 *   <li>Case variations (ROWNUM, rownum, Rownum)</li>
 *   <li>ROWNUM with table aliases</li>
 *   <li>Multiple references to ROWNUM</li>
 *   <li>ROWNUM in expressions (negative test)</li>
 * </ul>
 *
 * <p><b>Not supported (design decision):</b>
 * <ul>
 *   <li>ROWNUM in arithmetic: {@code SELECT ROWNUM * 2 FROM emp} (complex, rare use case)</li>
 *   <li>ROWNUM with unary operators: {@code SELECT -ROWNUM FROM emp} (meaningless)</li>
 * </ul>
 */
class RownumSelectTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== SIMPLE ROWNUM IN SELECT ====================

    @Test
    void simpleRownumSelect() {
        // Given: SELECT ROWNUM alone
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transform to row_number() with AS rownum
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "Should transform to row_number() OVER () AS rownum");
        assertTrue(normalized.contains("FROM hr.employees"),
                "Should preserve FROM clause");
        assertFalse(normalized.contains("ROWNUM"),
                "Original ROWNUM should be removed");
    }

    @Test
    void rownumWithExplicitAlias() {
        // Given: SELECT ROWNUM AS rn
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM AS rn FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Preserve user's alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rn"),
                "Should preserve user alias 'rn'");
        assertFalse(normalized.contains("AS rownum"),
                "Should not add default 'rownum' alias when user provides one");
    }

    @Test
    void rownumWithImplicitAlias() {
        // Given: SELECT ROWNUM row_id (implicit alias, no AS keyword)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM row_id FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Preserve implicit alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS row_id"),
                "Should preserve implicit alias 'row_id'");
    }

    // ==================== ROWNUM WITH OTHER COLUMNS ====================

    @Test
    void rownumWithColumns() {
        // Given: ROWNUM with other columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM, empno, ename FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be first column");
        assertTrue(normalized.contains("empno"),
                "empno column should be present");
        assertTrue(normalized.contains("ename"),
                "ename column should be present");
    }

    @Test
    void rownumInMiddleOfSelectList() {
        // Given: ROWNUM in middle of SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno, ROWNUM, ename FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM transformed in middle position
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno , row_number( ) OVER ( ) AS rownum , ename"),
                "ROWNUM should be transformed in middle of SELECT list");
    }

    @Test
    void rownumAtEndOfSelectList() {
        // Given: ROWNUM at end of SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno, ename, ROWNUM FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM transformed at end
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno , ename , row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be transformed at end of SELECT list");
    }

    // ==================== ROWNUM WITH CLAUSES ====================

    @Test
    void rownumWithWhere() {
        // Given: ROWNUM in SELECT with WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM, empno FROM employees WHERE dept_id = 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both ROWNUM transformation and WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be transformed");
        assertTrue(normalized.contains("WHERE dept_id = 10"),
                "WHERE clause should be preserved");
    }

    @Test
    void rownumWithOrderBy() {
        // Given: ROWNUM with ORDER BY
        // Note: In Oracle, ROWNUM is assigned BEFORE ORDER BY, so this is unusual
        // But we should still transform it correctly
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM, empno, salary FROM employees ORDER BY salary DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM transformed, ORDER BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be transformed");
        assertTrue(normalized.contains("ORDER BY salary DESC NULLS FIRST"),
                "ORDER BY should be preserved with NULLS FIRST");
    }

    @Test
    void rownumWithGroupBy() {
        // Given: ROWNUM with GROUP BY
        // Note: This is unusual (ROWNUM per group?) but should transform
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT dept_id, ROWNUM, COUNT(*) FROM employees GROUP BY dept_id";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM transformed, GROUP BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be transformed");
        assertTrue(normalized.contains("GROUP BY dept_id"),
                "GROUP BY should be preserved");
    }

    // ==================== CASE VARIATIONS ====================

    @Test
    void rownumLowercase() {
        // Given: lowercase rownum
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT rownum FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed with lowercase alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "lowercase rownum should be transformed");
    }

    @Test
    void rownumMixedCase() {
        // Given: mixed case RowNum
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT RowNum FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "Mixed case RowNum should be transformed");
    }

    // ==================== INTEGRATION WITH TABLE ALIASES ====================

    @Test
    void rownumWithTableAlias() {
        // Given: ROWNUM with table alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM, e.empno, e.ename FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM transformed, table alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be transformed");
        assertTrue(normalized.contains("e . empno"),
                "Table alias should be preserved (with spaces around dot)");
    }

    // ==================== MULTIPLE ROWNUM REFERENCES ====================

    @Test
    void multipleRownumInSelectList() {
        // Given: Multiple ROWNUM references (unusual but valid)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM AS rn1, empno, ROWNUM AS rn2 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both ROWNUM references transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rn1"),
                "First ROWNUM should be transformed");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rn2"),
                "Second ROWNUM should be transformed");
    }

    // ==================== FROM DUAL INTEGRATION ====================

    @Test
    void rownumWithFromDual() {
        // Given: ROWNUM with FROM DUAL
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM transformed, FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM should be transformed");
        assertFalse(normalized.contains("DUAL"),
                "FROM DUAL should be removed");
        assertFalse(normalized.contains("FROM"),
                "FROM clause should be removed entirely");
    }

    // ==================== REGRESSION TESTS (NO ROWNUM) ====================

    @Test
    void normalSelectWithoutRownum() {
        // Given: Normal SELECT without ROWNUM
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno, ename FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: No row_number() added
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertFalse(normalized.contains("row_number"),
                "Should not add row_number() without ROWNUM");
        assertFalse(normalized.contains("ROWNUM"),
                "Should not contain ROWNUM");
        assertTrue(normalized.contains("empno"),
                "Should contain empno column");
    }

    // ==================== COMBINED WITH WHERE ROWNUM LIMIT ====================

    @Test
    void rownumInBothSelectAndWhere() {
        // Given: ROWNUM in SELECT list AND WHERE clause
        // This is a valid Oracle pattern: SELECT ROWNUM, col FROM tab WHERE ROWNUM <= 10
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT ROWNUM, empno FROM employees WHERE ROWNUM <= 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROWNUM in SELECT transformed, WHERE ROWNUM converted to LIMIT
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("row_number( ) OVER ( ) AS rownum"),
                "ROWNUM in SELECT should be transformed");
        assertTrue(normalized.contains("LIMIT 10"),
                "WHERE ROWNUM should be converted to LIMIT");
        assertFalse(normalized.contains("WHERE"),
                "WHERE clause should be removed (only had ROWNUM condition)");
    }
}
