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
 * Tests for SUBSTR → SUBSTRING transformation.
 *
 * <p>Oracle uses SUBSTR for substring extraction with positional arguments.
 * PostgreSQL uses SUBSTRING with FROM/FOR keyword syntax.
 *
 * <h3>Oracle vs PostgreSQL:</h3>
 * <pre>
 * Oracle:     SUBSTR(string, position, length)
 * PostgreSQL: SUBSTRING(string FROM position FOR length)
 *
 * Oracle:     SUBSTR(string, position)
 * PostgreSQL: SUBSTRING(string FROM position)
 * </pre>
 */
class SubstrTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== BASIC SUBSTR QUERIES ====================

    @Test
    void substrWithTwoArguments() {
        // Given: SUBSTR with string and position only
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR('Hello World', 7) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SUBSTR → SUBSTRING with FROM
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'Hello World' FROM 7 )"),
                "Should transform SUBSTR to SUBSTRING with FROM syntax");
    }

    @Test
    void substrWithThreeArguments() {
        // Given: SUBSTR with string, position, and length
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR('Hello World', 7, 5) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SUBSTR → SUBSTRING with FROM and FOR
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'Hello World' FROM 7 FOR 5 )"),
                "Should transform SUBSTR to SUBSTRING with FROM and FOR syntax");
    }

    // ==================== COLUMN REFERENCES ====================

    @Test
    void substrWithColumnReference() {
        // Given: SUBSTR with column reference
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(name, 1, 10) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column reference preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( name FROM 1 FOR 10 )"),
                "Should preserve column reference in SUBSTRING");
    }

    @Test
    void substrWithQualifiedColumnReference() {
        // Given: SUBSTR with qualified column reference
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(e.name, 1, 10) FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified column reference preserved (note: spaces around dot)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( e . name FROM 1 FOR 10 )"),
                "Should preserve qualified column reference in SUBSTRING");
    }

    // ==================== NUMERIC EXPRESSIONS ====================

    @Test
    void substrWithNumericLiterals() {
        // Given: SUBSTR with numeric literals
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR('ABCDEFGH', 3, 4) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric literals preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'ABCDEFGH' FROM 3 FOR 4 )"),
                "Should preserve numeric literals");
    }

    @Test
    void substrWithArithmeticExpressions() {
        // Given: SUBSTR with arithmetic expressions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(name, 1 + 1, 10 - 2) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Arithmetic expressions preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( name FROM 1 + 1 FOR 10 - 2 )"),
                "Should preserve arithmetic expressions");
    }

    // ==================== NESTED FUNCTIONS ====================

    @Test
    void substrWithNestedSubstr() {
        // Given: Nested SUBSTR calls
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(SUBSTR(name, 1, 10), 3, 5) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both SUBSTR calls transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( SUBSTRING( name FROM 1 FOR 10 ) FROM 3 FOR 5 )"),
                "Should transform nested SUBSTR calls");
    }

    @Test
    void substrWithOtherFunctions() {
        // Given: SUBSTR combined with other functions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(UPPER(name), 1, 5) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: UPPER preserved (unqualified - built-in function), SUBSTR transformed
        // Note: UPPER remains as UPPER (unqualified, built-in function)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( UPPER( name ) FROM 1 FOR 5 )"),
                "Should combine UPPER with SUBSTRING");
    }

    // ==================== CASE VARIATIONS ====================

    @Test
    void substrLowercase() {
        // Given: lowercase substr
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT substr('Hello', 1, 3) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed to SUBSTRING (uppercase)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'Hello' FROM 1 FOR 3 )"),
                "Should transform lowercase substr");
    }

    @Test
    void substrMixedCase() {
        // Given: mixed case SubStr
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SubStr('Hello', 1, 3) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed to SUBSTRING (uppercase)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'Hello' FROM 1 FOR 3 )"),
                "Should transform mixed case SubStr");
    }

    // ==================== COMPLEX QUERIES ====================

    @Test
    void multipleSubstrInSelectList() {
        // Given: Multiple SUBSTR calls in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(first_name, 1, 1), SUBSTR(last_name, 1, 5) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both SUBSTR calls transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( first_name FROM 1 FOR 1 )"),
                "Should transform first SUBSTR");
        assertTrue(normalized.contains("SUBSTRING( last_name FROM 1 FOR 5 )"),
                "Should transform second SUBSTR");
    }

    @Test
    void substrInWhereClause() {
        // Given: SUBSTR in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT name FROM employees WHERE SUBSTR(name, 1, 1) = 'A'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SUBSTR in WHERE transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE SUBSTRING( name FROM 1 FOR 1 ) = 'A'"),
                "Should transform SUBSTR in WHERE clause");
    }

    @Test
    void substrWithColumnAlias() {
        // Given: SUBSTR with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(name, 1, 10) AS short_name FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( name FROM 1 FOR 10 ) AS short_name"),
                "Should preserve column alias");
    }

    // ==================== EDGE CASES ====================

    @Test
    void substrWithZeroPosition() {
        // Given: SUBSTR with position 0 (Oracle treats as 1)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR('Hello', 0, 3) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Position 0 preserved (PostgreSQL also treats 0 as 1)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'Hello' FROM 0 FOR 3 )"),
                "Should preserve position 0");
    }

    // Note: Negative position test commented out - requires unary operator support
    // which is not yet implemented (known limitation)
    //
    // @Test
    // void substrWithNegativePosition() {
    //     // Given: SUBSTR with negative position (Oracle counts from end)
    //     TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
    //     String oracleSql = "SELECT SUBSTR('Hello World', -5, 5) FROM employees";
    //
    //     // When: Parse and transform
    //     ParseResult parseResult = parser.parseSelectStatement(oracleSql);
    //     assertFalse(parseResult.hasErrors(), "Parse should succeed");
    //
    //     PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
    //     String postgresSql = builder.visit(parseResult.getTree());
    //
    //     // Then: Negative position preserved
    //     // Note: Oracle and PostgreSQL handle negative positions differently!
    //     // Oracle: -5 means "5 characters from the end"
    //     // PostgreSQL: negative position is not standard, behavior may differ
    //     // This test validates the transformation, not semantic equivalence
    //     String normalized = postgresSql.trim().replaceAll("\\s+", " ");
    //     assertTrue(normalized.contains("SUBSTRING( 'Hello World' FROM -5 FOR 5 )"),
    //             "Should preserve negative position (semantic difference may exist)");
    // }

    @Test
    void substrFromDual() {
        // Given: SUBSTR in scalar query (FROM DUAL)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR('Hello World', 7, 5) FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SUBSTR transformed and FROM DUAL removed
        // Note: "FROM" still appears in SUBSTRING syntax (FROM position FOR length)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SUBSTRING( 'Hello World' FROM 7 FOR 5 )"),
                "Should transform SUBSTR");
        assertFalse(normalized.contains("DUAL"),
                "Should remove FROM DUAL");
        assertEquals("SELECT SUBSTRING( 'Hello World' FROM 7 FOR 5 )", normalized,
                "Should not have FROM clause (only FROM in SUBSTRING syntax)");
    }
}
