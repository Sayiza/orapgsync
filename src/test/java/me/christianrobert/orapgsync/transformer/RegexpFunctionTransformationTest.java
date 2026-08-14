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
 *   <li>REGEXP_REPLACE(str, pattern, replacement) → REGEXP_REPLACE(str, pattern, replacement, 1, 0, 'p')</li>
 *   <li>REGEXP_SUBSTR(str, pattern) → REGEXP_SUBSTR(str, pattern, 1, 1, 'p')</li>
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

        // Then: Oracle's defaults (position 1, occurrence 0 = replace all) become explicit
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( phone , '[^0-9]' , '' , 1 , 0 , 'p' )"),
            "REGEXP_REPLACE should map to the positional form, got: " + normalized);
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

        // Then: Should map to the positional form
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( 'Hello123World456' , '[0-9]+' , 'X' , 1 , 0 , 'p' )"),
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

        assertTrue(normalized.contains("WHERE REGEXP_REPLACE( email , '@.*' , '' , 1 , 0 , 'p' )"),
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

        // Then: 'i' passes through, and the newline flag is appended
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , 'hello' , 'Hi' , 1 , 0 , 'ip' )"),
            "REGEXP_REPLACE with 'i' flag should become 'ip', got: " + normalized);
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

        // Then: occurrence 1 passes through, replacing only the first match
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , '[0-9]' , 'X' , 1 , 1 , 'p' )"),
            "REGEXP_REPLACE with occurrence=1 should keep occurrence 1, got: " + normalized);
        assertFalse(normalized.contains("'g'"),
            "Should not contain 'g' flag for first occurrence only");
    }

    @Test
    void regexpReplaceNthOccurrence() {
        // Given: REGEXP_REPLACE with occurrence > 1. This was rejected while the transformation
        // emitted PostgreSQL's 4-argument form, which cannot express "replace only the Nth match".
        // The 6-argument form added in PostgreSQL 15 can.
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, '[0-9]', 'X', 1, 2) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: occurrence maps straight through as PostgreSQL's N
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , '[0-9]' , 'X' , 1 , 2 , 'p' )"),
            "REGEXP_REPLACE should pass occurrence through, got: " + normalized);
    }

    @Test
    void regexpReplaceWithStartPosition() {
        // Given: REGEXP_REPLACE with position != 1, previously rejected for the same reason
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_REPLACE(text, '[0-9]', 'X', 5) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: position maps straight through as PostgreSQL's start
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_REPLACE( text , '[0-9]' , 'X' , 5 , 0 , 'p' )"),
            "REGEXP_REPLACE should pass position through, got: " + normalized);
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

        // Then: Should map to native REGEXP_SUBSTR with Oracle's defaults made explicit
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_SUBSTR( email , '[^@]+' , 1 , 1 , 'p' )"),
            "REGEXP_SUBSTR should map to native REGEXP_SUBSTR, got: " + normalized);
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

        assertTrue(normalized.contains("REGEXP_SUBSTR( 'test@example.com' , '[a-z]+' , 1 , 1 , 'p' )"),
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

        assertTrue(normalized.contains("WHERE REGEXP_SUBSTR( email , '[^@]+' , 1 , 1 , 'p' )"),
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

        assertTrue(normalized.contains("REGEXP_SUBSTR( text , '[A-Z]+' , 1 , 1 , 'ip' )"),
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

        assertTrue(normalized.contains("UPPER( REGEXP_SUBSTR( email , '[^@]+' , 1 , 1 , 'p' ) )"),
            "REGEXP_SUBSTR should work nested with UPPER, got: " + normalized);
    }

    @Test
    void regexpSubstrNthOccurrence() {
        // Given: REGEXP_SUBSTR with occurrence != 1. REGEXP_MATCH has no occurrence parameter,
        // so this had to be rejected; native REGEXP_SUBSTR takes it positionally.
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(text, '[0-9]+', 1, 2) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: occurrence maps straight through
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_SUBSTR( text , '[0-9]+' , 1 , 2 , 'p' )"),
            "REGEXP_SUBSTR should pass occurrence through, got: " + normalized);
    }

    @Test
    void regexpSubstrCapturingGroupReturnsWholeMatch() {
        // Given: a pattern with capture groups. This is the bug the REGEXP_MATCH emulation had:
        // REGEXP_MATCH returns the capture groups, so [1] yielded the first group ('2024'), while
        // Oracle REGEXP_SUBSTR returns the whole match ('2024-01'). Native REGEXP_SUBSTR agrees
        // with Oracle -- verified against PostgreSQL 17.
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(dt, '(\\d+)-(\\d+)') FROM events";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: no array indexing, so no capture-group truncation
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_SUBSTR( dt , '(\\d+)-(\\d+)' , 1 , 1 , 'p' )"),
            "REGEXP_SUBSTR should return the whole match, got: " + normalized);
        assertFalse(normalized.contains("[1]"),
            "REGEXP_SUBSTR must not index into capture groups, got: " + normalized);
    }

    @Test
    void regexpSubstrWithStartPosition() {
        // Given: REGEXP_SUBSTR with position != 1, previously rejected
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_SUBSTR(text, '[0-9]+', 5) FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: position maps straight through
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_SUBSTR( text , '[0-9]+' , 5 , 1 , 'p' )"),
            "REGEXP_SUBSTR should pass position through, got: " + normalized);
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

    // ==================== REGEXP_LIKE / REGEXP_COUNT Tests ====================

    @Test
    void regexpLikeMapsFlags() {
        // Given: REGEXP_LIKE in a WHERE clause. This previously passed through verbatim, which
        // resolved on PostgreSQL 15+ but silently kept PostgreSQL's newline defaults.
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT name FROM employees WHERE REGEXP_LIKE(email, '^[a-z]+@')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: the flags argument is added with Oracle's default newline handling
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_LIKE( email , '^[a-z]+@' , 'p' )"),
            "REGEXP_LIKE should get mapped flags, got: " + normalized);
    }

    @Test
    void regexpLikeCaseInsensitive() {
        // Given: REGEXP_LIKE with an explicit match parameter
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT name FROM employees WHERE REGEXP_LIKE(email, 'ADMIN', 'i')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: 'i' passes through, newline flag appended
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_LIKE( email , 'ADMIN' , 'ip' )"),
            "REGEXP_LIKE should map flags to 'ip', got: " + normalized);
    }

    @Test
    void regexpCountMapsPositionAndFlags() {
        // Given: REGEXP_COUNT, which takes position but no occurrence
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_COUNT(text, '[0-9]', 3, 'i') FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: position maps positionally, flags are mapped
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_COUNT( text , '[0-9]' , 3 , 'ip' )"),
            "REGEXP_COUNT should map position and flags, got: " + normalized);
    }

    @Test
    void regexpCountSimpleTwoArgs() {
        // Given: REGEXP_COUNT with Oracle's defaults
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT REGEXP_COUNT(text, '[0-9]') FROM messages";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: defaults made explicit so the flags argument can be supplied
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("REGEXP_COUNT( text , '[0-9]' , 1 , 'p' )"),
            "REGEXP_COUNT should emit the default position, got: " + normalized);
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

        assertTrue(normalized.contains("REGEXP_REPLACE( text , '[0-9]' , 'X' , 1 , 0 , 'p' )"),
            "REGEXP_REPLACE should be transformed, got: " + normalized);
        assertTrue(normalized.contains("REGEXP_SUBSTR( text , '[A-Z]+' , 1 , 1 , 'p' )"),
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

        assertTrue(normalized.contains("UPPER( REGEXP_REPLACE( email , '@.*' , '' , 1 , 0 , 'p' ) )"),
            "REGEXP_REPLACE with UPPER should work, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '@' IN email )"),
            "INSTR should also be transformed, got: " + normalized);
    }
}
