package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test case for direct AST-to-code transformation.
 * Tests the simplest possible query: SELECT col1, col2 FROM table
 *
 * This is a plain JUnit test (no Quarkus) that directly tests the parser and visitor.
 */
class SimpleSelectTransformationTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        builder = new PostgresCodeBuilder();
    }

    @Test
    void testSimpleSelectTwoColumns() {
        // Given: Oracle SQL with simple SELECT
        String oracleSql = "SELECT nr, text FROM example";

        // When: Parse and transform to PostgreSQL
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);

        // Then: Parse should succeed
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // And: Transform to PostgreSQL
        String postgresSql = builder.visit(parseResult.getTree());
        assertNotNull(postgresSql, "PostgreSQL SQL should not be null");

        // Normalize whitespace for comparison
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT nr , text FROM example", normalized,
            "Simple SELECT should transform directly");
    }

    @Test
    void testSimpleSelectWithTableAlias() {
        // Given: Oracle SQL with table alias
        String oracleSql = "SELECT nr, text FROM example e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Table alias should be preserved
        assertEquals("SELECT nr , text FROM example e", normalized,
            "Table alias should be preserved");
    }

    @Test
    void testSimpleSelectSingleColumn() {
        // Given: Oracle SQL with single column
        String oracleSql = "SELECT nr FROM example";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Should transform directly
        assertEquals("SELECT nr FROM example", normalized,
            "Single column SELECT should transform directly");
    }

    @Test
    void testParseError() {
        // Given: Invalid SQL
        String oracleSql = "SELECT FROM";  // Missing column list and table name

        // When: Parse
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);

        // Then: Should have parse errors
        assertTrue(parseResult.hasErrors(), "Should have parse errors");
        assertNotNull(parseResult.getErrorMessage(), "Should have error message");
    }

    @Test
    void testParenthesizedSubquery() {
        // Given: Oracle SQL wrapped in parentheses (like Oracle view definitions)
        String oracleSql = "(SELECT nr, text FROM example)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Should preserve parentheses
        assertEquals("(SELECT nr , text FROM example)", normalized,
            "Parenthesized subquery should be preserved");
    }

    @Test
    void testParenthesizedSubqueryWithAlias() {
        // Given: Oracle SQL with parenthesized subquery and table alias
        String oracleSql = "(SELECT nr, text FROM example e)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Then: Should preserve both parentheses and alias
        assertEquals("(SELECT nr , text FROM example e)", normalized,
            "Parenthesized subquery with alias should be preserved");
    }
}
