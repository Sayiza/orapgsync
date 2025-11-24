package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FROM DUAL transformation.
 *
 * <p>Oracle uses DUAL (a special single-row table) for queries without real tables.
 * PostgreSQL doesn't need a FROM clause for scalar expressions.
 *
 * <h3>Oracle vs PostgreSQL:</h3>
 * <pre>
 * Oracle:     SELECT SYSDATE FROM DUAL
 * PostgreSQL: SELECT CURRENT_TIMESTAMP
 *
 * Oracle:     SELECT 1 + 1 FROM DUAL
 * PostgreSQL: SELECT 1 + 1
 * </pre>
 */
class FromDualTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== BASIC DUAL QUERIES ====================

    @Test
    void simpleNumericExpression() {
        // Given: Simple numeric constant
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1", normalized, "FROM DUAL should be removed");
        assertFalse(normalized.contains("FROM"), "Should not have FROM clause");
    }

    @Test
    void sysdateFromDual() {
        // Given: SYSDATE pseudo-column
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SYSDATE FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SYSDATE → CURRENT_TIMESTAMP and FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT CURRENT_TIMESTAMP", normalized,
                "Should transform SYSDATE and remove FROM DUAL");
    }

    @Test
    void stringLiteralFromDual() {
        // Given: String literal
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 'Hello World' FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 'Hello World'", normalized, "FROM DUAL should be removed");
    }

    @Test
    void arithmeticExpressionFromDual() {
        // Given: Arithmetic expression
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 + 1 FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1 + 1", normalized, "FROM DUAL should be removed");
    }

    // ==================== CASE VARIATIONS ====================

    @Test
    void dualLowercase() {
        // Given: lowercase dual
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 FROM dual";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM dual removed (case-insensitive)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1", normalized, "FROM dual (lowercase) should be removed");
    }

    @Test
    void dualMixedCase() {
        // Given: Mixed case Dual
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 FROM Dual";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM Dual removed (case-insensitive)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1", normalized, "FROM Dual (mixed case) should be removed");
    }

    @Test
    void sysDualQualified() {
        // Given: Qualified SYS.DUAL
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 FROM SYS.DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM SYS.DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1", normalized, "FROM SYS.DUAL should be removed");
    }

    // ==================== MULTIPLE EXPRESSIONS ====================

    @Test
    void multipleExpressionsFromDual() {
        // Given: Multiple SELECT list expressions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1, 2, 3 FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1 , 2 , 3", normalized, "FROM DUAL should be removed");
    }

    @Test
    void functionCallFromDual() {
        // Given: Function call (NVL)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT NVL(NULL, 0) FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NVL → COALESCE and FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT COALESCE( NULL , 0 )", normalized,
                "Should transform function and remove FROM DUAL");
    }

    // ==================== WITH COLUMN ALIASES ====================

    @Test
    void dualWithColumnAlias() {
        // Given: Column alias on scalar expression
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 AS num FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed, alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1 AS num", normalized,
                "FROM DUAL should be removed, alias preserved");
    }

    // ==================== COMPLEX EXPRESSIONS ====================

    @Test
    void complexExpressionFromDual() {
        // Given: Complex expression with CASE
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT CASE WHEN 1 = 1 THEN 'YES' ELSE 'NO' END FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE transformed and FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.startsWith("SELECT CASE"),
                "Should start with SELECT CASE");
        assertFalse(normalized.contains("FROM"),
                "Should not have FROM clause");
    }

    @Test
    void concatenationFromDual() {
        // Given: String concatenation
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 'Hello' || ' ' || 'World' FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: || → CONCAT() and FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.startsWith("SELECT CONCAT"),
                "Should start with SELECT CONCAT");
        assertFalse(normalized.contains("FROM"),
                "Should not have FROM clause");
    }

    // ==================== NEGATIVE TESTS (NOT DUAL) ====================

    @Test
    void regularTableShouldKeepFromClause() {
        // Given: Regular table (not DUAL)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM clause preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("FROM"), "Should have FROM clause");
        assertTrue(normalized.contains("hr.employees"), "Should have schema-qualified table");
    }

    @Test
    void multipleTablesShouldKeepFromClause() {
        // Given: Multiple tables including DUAL
        // Note: This is unusual but theoretically possible
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno FROM employees, DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM clause preserved (not DUAL-only)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("FROM"), "Should have FROM clause with multiple tables");
    }

    // ==================== EDGE CASES ====================

    @Test
    void dualWithWhereClause() {
        // Given: DUAL with WHERE clause (unusual but valid)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 FROM DUAL WHERE 1 = 1";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed, WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 1 WHERE 1 = 1", normalized,
                "FROM DUAL should be removed, WHERE clause preserved");
    }

    @Test
    void dualWithOrderBy() {
        // Given: DUAL with ORDER BY (unusual but valid)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT 1 FROM DUAL ORDER BY 1";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FROM DUAL removed, ORDER BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.matches("SELECT 1 ORDER BY 1( DESC NULLS FIRST)?"),
                "FROM DUAL should be removed, ORDER BY preserved");
    }
}
