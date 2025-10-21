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
 * Tests for Oracle INSTR → PostgreSQL POSITION/STRPOS transformation.
 *
 * <p>Oracle INSTR syntax:
 * <pre>
 * INSTR(string, substring [, position [, occurrence]])
 * </pre>
 *
 * <p>Parameters:
 * <ul>
 *   <li>string: Source string to search in</li>
 *   <li>substring: Substring to find</li>
 *   <li>position (optional): Starting position, default 1 (can be negative for reverse)</li>
 *   <li>occurrence (optional): Which occurrence to find, default 1</li>
 * </ul>
 *
 * <p>Returns: Position of substring (1-indexed), or 0 if not found
 *
 * <h3>Transformation Strategy:</h3>
 * <ul>
 *   <li>2 args: INSTR(str, substr) → POSITION(substr IN str)</li>
 *   <li>3 args: INSTR(str, substr, pos) → More complex (SUBSTRING + POSITION + offset)</li>
 *   <li>4 args: INSTR(str, substr, pos, occ) → Very complex (may need regexp or custom function)</li>
 * </ul>
 */
public class InstrTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== 2-Argument INSTR (Simple Case) ====================

    @Test
    void instrSimpleTwoArgs() {
        // Given: INSTR with just string and substring
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, '@') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: INSTR should be transformed to POSITION
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( '@' IN email )"),
            "INSTR(email, '@') should transform to POSITION('@' IN email), got: " + normalized);
    }

    @Test
    void instrWithStringLiterals() {
        // Given: INSTR with string literals
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR('Hello World', 'World') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: INSTR should be transformed to POSITION
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( 'World' IN 'Hello World' )"),
            "Should transform to POSITION with swapped arguments, got: " + normalized);
    }

    @Test
    void instrWithColumnReferences() {
        // Given: INSTR with column references
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(first_name, last_name) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: INSTR should be transformed to POSITION
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( last_name IN first_name )"),
            "Should transform to POSITION with swapped column refs, got: " + normalized);
    }

    @Test
    void instrInWhereClause() {
        // Given: INSTR in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE INSTR(email, '@gmail.com') > 0";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: INSTR should be transformed in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE POSITION( '@gmail.com' IN email ) > 0"),
            "Should transform INSTR in WHERE clause, got: " + normalized);
    }

    @Test
    void instrWithAlias() {
        // Given: INSTR with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, '@') AS at_position FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( '@' IN email ) AS at_position"),
            "Should preserve column alias, got: " + normalized);
    }

    // ==================== 3-Argument INSTR (With Starting Position) ====================

    @Test
    void instrWithStartingPosition() {
        // Given: INSTR with starting position
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, '.', 5) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should use CASE WHEN with SUBSTRING + POSITION
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Expected pattern:
        // CASE WHEN 5 > 0 AND 5 <= LENGTH(email)
        //      THEN POSITION('.' IN SUBSTRING(email FROM 5)) + 5 - 1
        //      ELSE 0
        // END
        assertTrue(normalized.contains("CASE WHEN 5 > 0"),
            "Should have CASE WHEN for position validation, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '.' IN SUBSTRING( email FROM 5 ) )"),
            "Should use SUBSTRING with starting position, got: " + normalized);
        assertTrue(normalized.contains("+ 5 - 1") || normalized.contains("+ ( 5 - 1 )"),
            "Should adjust result by offset, got: " + normalized);
    }

    @Test
    void instrWithStartingPositionLiteral() {
        // Given: INSTR with starting position as literal
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR('Hello World', 'o', 6) FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should handle literal starting position
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CASE WHEN"),
            "Should use CASE WHEN, got: " + normalized);
        assertTrue(normalized.contains("SUBSTRING( 'Hello World' FROM 6 )"),
            "Should use SUBSTRING from position 6, got: " + normalized);
    }

    // ==================== 4-Argument INSTR (With Occurrence) ====================

    @Test
    void instrWithOccurrence() {
        // Given: INSTR with starting position and occurrence
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, '.', 1, 2) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should use complex regexp or custom function
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // For now, expect a regexp-based solution or custom function call
        // This is the most complex case - may need special handling
        assertTrue(normalized.contains("instr_with_occurrence") || normalized.contains("REGEXP"),
            "Should use helper function or regexp for occurrence parameter, got: " + normalized);
    }

    // ==================== Edge Cases ====================

    @Test
    void instrNotFound() {
        // Given: INSTR where substring doesn't exist (should return 0)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, 'xyz') FROM employees WHERE INSTR(email, 'xyz') = 0";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: POSITION returns 0 when not found (same as Oracle)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( 'xyz' IN email )"),
            "Should transform both INSTR calls, got: " + normalized);
    }

    @Test
    void instrCaseSensitive() {
        // Given: INSTR is case-sensitive (like POSITION)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, 'GMAIL') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Case sensitivity preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( 'GMAIL' IN email )"),
            "Should preserve case sensitivity, got: " + normalized);
    }

    @Test
    void instrWithConcatenation() {
        // Given: INSTR with concatenated strings
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(first_name || ' ' || last_name, ' ') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should handle concatenation
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("POSITION( ' ' IN"),
            "Should transform INSTR with concatenation, got: " + normalized);
        // Note: || is transformed to CONCAT() for correct Oracle NULL handling semantics
        // (Oracle treats NULL as empty string, PostgreSQL || propagates NULL)
        assertTrue(normalized.contains("CONCAT("),
            "Should transform concatenation to CONCAT(), got: " + normalized);
    }

    @Test
    void instrNested() {
        // Given: Nested INSTR calls
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, '.', INSTR(email, '@') + 1) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both INSTR calls should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // The inner INSTR should be simple POSITION, outer should use CASE WHEN
        int positionCount = normalized.split("POSITION\\(", -1).length - 1;
        assertTrue(positionCount >= 2,
            "Should have at least 2 POSITION calls for nested INSTR, got: " + normalized);
    }

    @Test
    void instrInOrderBy() {
        // Given: INSTR in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees ORDER BY INSTR(email, '@') DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: INSTR should work in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("ORDER BY POSITION( '@' IN email ) DESC"),
            "Should transform INSTR in ORDER BY, got: " + normalized);
    }

    @Test
    void multipleInstrInSelect() {
        // Given: Multiple INSTR calls in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT INSTR(email, '@'), INSTR(email, '.') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both INSTR calls should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        int positionCount = normalized.split("POSITION\\(", -1).length - 1;
        assertEquals(2, positionCount,
            "Should have 2 POSITION calls for 2 INSTR, got: " + normalized);
    }
}
