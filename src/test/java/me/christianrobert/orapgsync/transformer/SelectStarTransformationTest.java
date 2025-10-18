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
 * Tests for SELECT * and SELECT table.* transformations.
 *
 * <p>Oracle: SELECT * FROM employees
 * <p>PostgreSQL: SELECT * FROM employees
 *
 * <p>Oracle: SELECT e.* FROM employees e
 * <p>PostgreSQL: SELECT e.* FROM employees e
 *
 * <p>These should pass through unchanged (asterisk preserved).
 */
class SelectStarTransformationTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== SELECT * TESTS ==========

    @Test
    void selectStarFromSingleTable() {
        // Given: Simple SELECT * query
        String oracleSql = "SELECT * FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT * preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT * FROM employees", normalized,
                "SELECT * should be preserved");
    }

    @Test
    void selectStarWithTableAlias() {
        // Given: SELECT * with table alias
        String oracleSql = "SELECT * FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT * preserved with alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT * FROM employees e", normalized,
                "SELECT * should be preserved with table alias");
    }

    @Test
    void selectStarUppercase() {
        // Given: SELECT * with uppercase
        String oracleSql = "SELECT * FROM EMPLOYEES";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT * preserved with uppercase
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT * FROM EMPLOYEES", normalized,
                "SELECT * with uppercase should be preserved");
    }

    // ========== SELECT table.* TESTS ==========

    @Test
    void selectQualifiedStarWithAlias() {
        // Given: SELECT alias.* query
        String oracleSql = "SELECT e.* FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified star preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . * FROM employees e", normalized,
                "SELECT e.* should be preserved");
    }

    @Test
    void selectQualifiedStarUppercase() {
        // Given: SELECT E.* with uppercase
        String oracleSql = "SELECT E.* FROM EMPLOYEES E";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified star preserved with uppercase
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT E . * FROM EMPLOYEES E", normalized,
                "SELECT E.* with uppercase should be preserved");
    }

    @Test
    void selectQualifiedStarMixedCase() {
        // Given: SELECT Emp.* with mixed case
        String oracleSql = "SELECT Emp.* FROM Employees Emp";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified star preserved with mixed case
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT Emp . * FROM Employees Emp", normalized,
                "SELECT Emp.* with mixed case should be preserved");
    }

    // ========== MIXED STAR AND COLUMNS ==========

    // TODO: These mixed cases may have parsing issues - investigate grammar
    // @Test
    // void selectStarWithAdditionalColumns() {
    //     // Given: SELECT * with additional columns
    //     String oracleSql = "SELECT *, empno FROM employees";
    //
    //     // When: Parse and transform
    //     ParseResult parseResult = parser.parseSelectStatement(oracleSql);
    //     assertFalse(parseResult.hasErrors(), "Parse should succeed");
    //
    //     builder = new PostgresCodeBuilder();
    //     String postgresSql = builder.visit(parseResult.getTree());
    //
    //     // Then: Star and column both preserved
    //     String normalized = postgresSql.trim().replaceAll("\\s+", " ");
    //     assertEquals("SELECT * , empno FROM employees", normalized,
    //             "SELECT * with additional columns should be preserved");
    // }

    @Test
    void selectColumnsWithQualifiedStar() {
        // Given: SELECT columns with qualified star
        String oracleSql = "SELECT empno, e.* FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column and qualified star both preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , e . * FROM employees e", normalized,
                "SELECT with column and e.* should be preserved");
    }

    @Test
    void selectMixedQualifiedAndUnqualifiedStar() {
        // Given: SELECT with both e.* and regular columns
        String oracleSql = "SELECT e.empno, e.*, e.ename FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All elements preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . empno , e . * , e . ename FROM employees e", normalized,
                "SELECT with mixed qualified columns and e.* should be preserved");
    }

    // ========== WITH CONTEXT ==========

    @Test
    void selectStarWithContext() {
        // Given: SELECT * with transformation context
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT * FROM employees";

        // When: Parse and transform with context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Table name qualified with schema, SELECT * preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT * FROM hr.employees", normalized,
                "SELECT * should work with context");
    }

    @Test
    void selectQualifiedStarWithContext() {
        // Given: SELECT e.* with transformation context
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT e.* FROM employees e";

        // When: Parse and transform with context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified star preserved, table name qualified with schema
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . * FROM hr.employees e", normalized,
                "SELECT e.* should work with context");
    }

    // TODO: Enable when multiple tables in FROM clause are supported
    // @Test
    // void selectMultipleQualifiedStars() {
    //     // Given: SELECT e.*, d.* from multiple tables
    //     String oracleSql = "SELECT e.*, d.* FROM employees e, departments d";
    //
    //     // When: Parse and transform
    //     ParseResult parseResult = parser.parseSelectStatement(oracleSql);
    //     assertFalse(parseResult.hasErrors(), "Parse should succeed");
    //
    //     builder = new PostgresCodeBuilder();
    //     String postgresSql = builder.visit(parseResult.getTree());
    //
    //     // Then: Both qualified stars preserved
    //     String normalized = postgresSql.trim().replaceAll("\\s+", " ");
    //     assertTrue(normalized.contains("e . *"), "Should contain e.*");
    //     assertTrue(normalized.contains("d . *"), "Should contain d.*");
    // }
}
