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
 * Tests for TRIM function transformation.
 *
 * <p>Oracle and PostgreSQL have nearly identical TRIM syntax (pass-through strategy).
 *
 * <h3>Oracle vs PostgreSQL:</h3>
 * <pre>
 * Oracle:     TRIM(string)
 * PostgreSQL: TRIM(string)
 *
 * Oracle:     TRIM(LEADING FROM string)
 * PostgreSQL: TRIM(LEADING FROM string)
 *
 * Oracle:     TRIM(TRAILING FROM string)
 * PostgreSQL: TRIM(TRAILING FROM string)
 *
 * Oracle:     TRIM(BOTH FROM string)
 * PostgreSQL: TRIM(BOTH FROM string)
 *
 * Oracle:     TRIM('x' FROM string)
 * PostgreSQL: TRIM('x' FROM string)
 *
 * Oracle:     TRIM(LEADING 'x' FROM string)
 * PostgreSQL: TRIM(LEADING 'x' FROM string)
 * </pre>
 */
class TrimTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== BASIC TRIM QUERIES ====================

    @Test
    void trimWithDefaultBehavior() {
        // Given: TRIM with just string (removes leading and trailing spaces)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM preserved as-is (identical syntax)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( name )"),
                "Should preserve simple TRIM");
    }

    @Test
    void trimWithStringLiteral() {
        // Given: TRIM with string literal
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM('  Hello World  ') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM preserved as-is
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( ' Hello World ' )"),
                "Should preserve TRIM with string literal");
    }

    // ==================== LEADING/TRAILING/BOTH ====================

    @Test
    void trimLeadingSpaces() {
        // Given: TRIM LEADING (removes leading spaces only)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(LEADING FROM name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM LEADING preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( LEADING FROM name )"),
                "Should preserve TRIM LEADING FROM");
    }

    @Test
    void trimTrailingSpaces() {
        // Given: TRIM TRAILING (removes trailing spaces only)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(TRAILING FROM name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM TRAILING preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( TRAILING FROM name )"),
                "Should preserve TRIM TRAILING FROM");
    }

    @Test
    void trimBothSpaces() {
        // Given: TRIM BOTH (removes both sides - explicit)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(BOTH FROM name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM BOTH preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( BOTH FROM name )"),
                "Should preserve TRIM BOTH FROM");
    }

    // ==================== TRIM CHARACTER ====================

    @Test
    void trimSpecificCharacter() {
        // Given: TRIM with specific character to remove
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM('x' FROM name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Trim character preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( 'x' FROM name )"),
                "Should preserve TRIM 'x' FROM");
    }

    @Test
    void trimLeadingSpecificCharacter() {
        // Given: TRIM LEADING with specific character
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(LEADING '0' FROM account_number) FROM accounts";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM LEADING '0' FROM preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( LEADING '0' FROM account_number )"),
                "Should preserve TRIM LEADING '0' FROM");
    }

    @Test
    void trimTrailingSpecificCharacter() {
        // Given: TRIM TRAILING with specific character
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(TRAILING '.' FROM name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM TRAILING '.' FROM preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( TRAILING '.' FROM name )"),
                "Should preserve TRIM TRAILING '.' FROM");
    }

    @Test
    void trimBothSpecificCharacter() {
        // Given: TRIM BOTH with specific character
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(BOTH '*' FROM description) FROM products";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM BOTH '*' FROM preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( BOTH '*' FROM description )"),
                "Should preserve TRIM BOTH '*' FROM");
    }

    // ==================== COLUMN REFERENCES ====================

    @Test
    void trimWithQualifiedColumn() {
        // Given: TRIM with qualified column reference
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(e.name) FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified column reference preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( e . name )"),
                "Should preserve qualified column in TRIM");
    }

    // ==================== WHERE CLAUSE ====================

    @Test
    void trimInWhereClause() {
        // Given: TRIM in WHERE clause comparison
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno FROM employees WHERE TRIM(name) = 'John'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM in WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE TRIM( name ) = 'John'"),
                "Should preserve TRIM in WHERE clause");
    }

    // ==================== COLUMN ALIASES ====================

    @Test
    void trimWithColumnAlias() {
        // Given: TRIM with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(name) AS clean_name FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( name ) AS clean_name"),
                "Should preserve column alias with TRIM");
    }

    // ==================== MULTIPLE TRIM CALLS ====================

    @Test
    void multipleTrimInSelectList() {
        // Given: Multiple TRIM calls in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(first_name), TRIM(last_name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both TRIM calls preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( first_name )"),
                "Should preserve first TRIM");
        assertTrue(normalized.contains("TRIM( last_name )"),
                "Should preserve second TRIM");
    }

    // ==================== CASE VARIATIONS ====================

    @Test
    void trimLowercase() {
        // Given: lowercase trim
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT trim(name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed to TRIM (uppercase)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( name )"),
                "Should preserve lowercase trim as TRIM");
    }

    @Test
    void trimMixedCase() {
        // Given: mixed case Trim
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT Trim(name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed to TRIM (uppercase)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( name )"),
                "Should preserve mixed case Trim as TRIM");
    }

    // ==================== FROM DUAL ====================

    @Test
    void trimFromDual() {
        // Given: TRIM in scalar query (FROM DUAL)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM('  Hello  ') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM preserved and FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( ' Hello ' )"),
                "Should preserve TRIM");
        assertFalse(normalized.contains("DUAL"),
                "Should remove FROM DUAL");
    }

    // ==================== ORDER BY ====================

    @Test
    void trimInOrderBy() {
        // Given: TRIM in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno FROM employees ORDER BY TRIM(name) ASC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRIM in ORDER BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY TRIM( name ) ASC"),
                "Should preserve TRIM in ORDER BY");
    }

    // ==================== NESTED FUNCTIONS ====================

    @Test
    void trimWithNestedFunction() {
        // Given: TRIM with nested UPPER function
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TRIM(UPPER(name)) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions preserved (UPPER is schema-qualified)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TRIM( hr.upper( name ) )"),
                "Should preserve TRIM with nested UPPER");
    }

    @Test
    void upperWithNestedTrim() {
        // Given: UPPER with nested TRIM
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT UPPER(TRIM(name)) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("hr.upper( TRIM( name ) )"),
                "Should preserve UPPER with nested TRIM");
    }
}
