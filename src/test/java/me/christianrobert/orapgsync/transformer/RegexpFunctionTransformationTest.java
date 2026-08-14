package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle REGEXP functions → PostgreSQL equivalents.
 *
 * <p>Tests transformation of:
 * <ul>
 *   <li>REGEXP_REPLACE(str, pattern, replacement) → REGEXP_REPLACE(str, pattern, replacement, 'g')</li>
 *   <li>REGEXP_SUBSTR(str, pattern) → (REGEXP_MATCH(str, pattern))[1]</li>
 *   <li>REGEXP_INSTR(str, pattern) → REGEXP_INSTR(str, pattern, 1, 1, 0, 'p')</li>
 * </ul>
 */
public class RegexpFunctionTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== REGEXP_REPLACE Tests ====================

    @Test
    void regexpReplaceSimpleThreeArgs() {
        // Given: REGEXP_REPLACE with 3 arguments (simple case)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(phone, '[^0-9]', '') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should add 'g' flag for global replacement
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( phone , '[^0-9]' , '' , 'g' )"),
            "REGEXP_REPLACE should add 'g' flag, got: " + normalized);
    }

    @Test
    void regexpReplaceWithLiterals() {
        // Given: REGEXP_REPLACE with string literals
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE('Hello123World456', '[0-9]+', 'X') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform with 'g' flag
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( 'Hello123World456' , '[0-9]+' , 'X' , 'g' )"),
            "REGEXP_REPLACE with literals should work, got: " + normalized);
    }

    @Test
    void regexpReplaceInWhereClause() {
        // Given: REGEXP_REPLACE used in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT name FROM employees WHERE REGEXP_REPLACE(email, '@.*', '') = 'john.doe'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: REGEXP_REPLACE should be in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE REGEXP_REPLACE( email , '@.*' , '' , 'g' )"),
            "REGEXP_REPLACE should work in WHERE clause, got: " + normalized);
    }

    @Test
    void regexpReplaceCaseInsensitive() {
        // Given: REGEXP_REPLACE with case-insensitive flag
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, 'hello', 'Hi', 1, 0, 'i') FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should combine 'g' and 'i' flags
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , 'hello' , 'Hi' , 'gi' )"),
            "REGEXP_REPLACE with 'i' flag should become 'gi', got: " + normalized);
    }

    @Test
    void regexpReplaceFirstOccurrenceOnly() {
        // Given: REGEXP_REPLACE with occurrence=1 (replace first match only)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, '[0-9]', 'X', 1, 1) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should NOT have 'g' flag (replace first only)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , '[0-9]' , 'X' )"),
            "REGEXP_REPLACE with occurrence=1 should not have 'g' flag, got: " + normalized);
        assertFalse(normalized.contains("'g'"),
            "Should not contain 'g' flag for first occurrence only");
    }

    @Test
    void regexpReplaceUnsupportedOccurrence() {
        // Given: REGEXP_REPLACE with occurrence > 1 (not supported)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, '[0-9]', 'X', 1, 2) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // Then: Should throw TransformationException for unsupported occurrence
        TransformationException exception = assertThrows(TransformationException.class, () -> {
            builder.visit(parseResult.getTree());
        });

        assertTrue(exception.getMessage().contains("occurrence"),
            "Exception should mention 'occurrence': " + exception.getMessage());
    }

    @Test
    void regexpReplaceUnsupportedPosition() {
        // Given: REGEXP_REPLACE with position != 1 (not supported)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, '[0-9]', 'X', 5) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // Then: Should throw TransformationException for unsupported position
        TransformationException exception = assertThrows(TransformationException.class, () -> {
            builder.visit(parseResult.getTree());
        });

        assertTrue(exception.getMessage().contains("position"),
            "Exception should mention 'position': " + exception.getMessage());
    }

    // ==================== REGEXP_SUBSTR Tests ====================

    @Test
    void regexpSubstrSimpleTwoArgs() {
        // Given: REGEXP_SUBSTR with 2 arguments (simple case)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(email, '[^@]+') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should transform to (REGEXP_MATCH())[1]
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( REGEXP_MATCH( email , '[^@]+' ) )[1]"),
            "REGEXP_SUBSTR should transform to (REGEXP_MATCH())[1], got: " + normalized);
    }

    @Test
    void regexpSubstrWithLiterals() {
        // Given: REGEXP_SUBSTR with string literals
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR('test@example.com', '[a-z]+') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should work with literals
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( REGEXP_MATCH( 'test@example.com' , '[a-z]+' ) )[1]"),
            "REGEXP_SUBSTR with literals should work, got: " + normalized);
    }

    @Test
    void regexpSubstrInWhereClause() {
        // Given: REGEXP_SUBSTR in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT name FROM employees WHERE REGEXP_SUBSTR(email, '[^@]+') = 'john.doe'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should work in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE ( REGEXP_MATCH( email , '[^@]+' ) )[1]"),
            "REGEXP_SUBSTR should work in WHERE clause, got: " + normalized);
    }

    @Test
    void regexpSubstrCaseInsensitive() {
        // Given: REGEXP_SUBSTR with case-insensitive flag
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(text, '[A-Z]+', 1, 1, 'i') FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should include 'i' flag
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( REGEXP_MATCH( text , '[A-Z]+' , 'i' ) )[1]"),
            "REGEXP_SUBSTR with 'i' flag should work, got: " + normalized);
    }

    @Test
    void regexpSubstrNested() {
        // Given: REGEXP_SUBSTR nested with other functions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT UPPER(REGEXP_SUBSTR(email, '[^@]+')) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should work nested (UPPER remains unqualified - it's a built-in function)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("UPPER( ( REGEXP_MATCH( email , '[^@]+' ) )[1] )"),
            "REGEXP_SUBSTR should work nested with UPPER, got: " + normalized);
    }

    @Test
    void regexpSubstrUnsupportedOccurrence() {
        // Given: REGEXP_SUBSTR with occurrence != 1 (not supported)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(text, '[0-9]+', 1, 2) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // Then: Should throw TransformationException
        TransformationException exception = assertThrows(TransformationException.class, () -> {
            builder.visit(parseResult.getTree());
        });

        assertTrue(exception.getMessage().contains("occurrence"),
            "Exception should mention 'occurrence': " + exception.getMessage());
    }

    @Test
    void regexpSubstrUnsupportedPosition() {
        // Given: REGEXP_SUBSTR with position != 1 (not supported)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(text, '[0-9]+', 5) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // Then: Should throw TransformationException
        TransformationException exception = assertThrows(TransformationException.class, () -> {
            builder.visit(parseResult.getTree());
        });

        assertTrue(exception.getMessage().contains("position"),
            "Exception should mention 'position': " + exception.getMessage());
    }

    // ==================== REGEXP_INSTR Tests ====================

    @Test
    void regexpInstrSimpleTwoArgs() {
        // Given: REGEXP_INSTR with 2 arguments (simple case)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_INSTR(email, '@') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Should map to native REGEXP_INSTR with Oracle's defaults made explicit.
        // The 'p' flag is Oracle's default newline handling, which PostgreSQL does not default to.
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_INSTR( email , '@' , 1 , 1 , 0 , 'p' )"),
            "REGEXP_INSTR should map to native REGEXP_INSTR, got: " + normalized);
    }

    @Test
    void regexpInstrWithPositionAndOccurrence() {
        // Given: REGEXP_INSTR with position and occurrence - rejected before, now passes through
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_INSTR(text, '[0-9]+', 5, 2) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: position and occurrence map positionally
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_INSTR( text , '[0-9]+' , 5 , 2 , 0 , 'p' )"),
            "REGEXP_INSTR should pass position/occurrence through, got: " + normalized);
    }

    @Test
    void regexpInstrWithReturnOptionAndFlags() {
        // Given: REGEXP_INSTR with return_option and a match parameter
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_INSTR(text, '[a-z]+', 1, 1, 1, 'i') FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: 'i' is passed through and the newline flag is appended
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_INSTR( text , '[a-z]+' , 1 , 1 , 1 , 'ip' )"),
            "REGEXP_INSTR should map flags to 'ip', got: " + normalized);
    }

    @Test
    void regexpInstrWithSubexpr() {
        // Given: REGEXP_INSTR with all 7 arguments including subexpr
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_INSTR(dt, '(\\d+)-(\\d+)', 1, 1, 0, 'c', 2) FROM events";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: subexpr is emitted as the 7th argument
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains(", 'cp' , 2 )"),
            "REGEXP_INSTR should emit subexpr after the flags, got: " + normalized);
    }

    @Test
    void regexpInstrCastsNonLiteralPositionToInteger() {
        // Given: REGEXP_INSTR whose position is a column, not a literal.
        // PostgreSQL resolves overloads by exact type and has no implicit numeric->integer cast,
        // so an uncast column reference would fail with "function ... does not exist".
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_INSTR(text, '[0-9]+', start_pos) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: the non-literal argument is cast
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( start_pos )::integer"),
            "Non-literal position should be cast to integer, got: " + normalized);
    }

    @Test
    void regexpInstrRejectsNonLiteralMatchParameter() {
        // Given: REGEXP_INSTR whose match_parameter is not a literal.
        // Flags must be mapped at transformation time; passing them through unmapped would
        // silently change matching semantics, so this must fail loudly instead.
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_INSTR(text, '[0-9]+', 1, 1, 0, flag_col) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        // Then: Should throw rather than emit unmapped flags
        TransformationException exception = assertThrows(TransformationException.class, () -> {
            builder.visit(parseResult.getTree());
        });

        assertTrue(exception.getMessage().contains("match_parameter"),
            "Exception should mention 'match_parameter': " + exception.getMessage());
    }

    // ==================== Mixed/Integration Tests ====================

    @Test
    void regexpReplaceAndRegexpSubstrTogether() {
        // Given: Both REGEXP_REPLACE and REGEXP_SUBSTR in same query
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, '[0-9]', 'X'), REGEXP_SUBSTR(text, '[A-Z]+') FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both should be transformed correctly
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , '[0-9]' , 'X' , 'g' )"),
            "REGEXP_REPLACE should be transformed, got: " + normalized);
        assertTrue(normalized.contains("( REGEXP_MATCH( text , '[A-Z]+' ) )[1]"),
            "REGEXP_SUBSTR should be transformed, got: " + normalized);
    }

    @Test
    void regexpWithOtherStringFunctions() {
        // Given: REGEXP functions with other string functions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT UPPER(REGEXP_REPLACE(email, '@.*', '')), INSTR(email, '@') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All functions should be transformed (UPPER remains unqualified - it's a built-in function)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("UPPER( REGEXP_REPLACE( email , '@.*' , '' , 'g' ) )"),
            "REGEXP_REPLACE with UPPER should work, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '@' IN email )"),
            "INSTR should also be transformed, got: " + normalized);
    }
}
