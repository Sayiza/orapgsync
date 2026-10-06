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
 * Tests for basic Oracle string functions → PostgreSQL transformation.
 *
 * <p>Covers simple pass-through string functions:
 * <ul>
 *   <li>LPAD(str, len[, pad]) - Left pad string to length</li>
 *   <li>RPAD(str, len[, pad]) - Right pad string to length</li>
 *   <li>TRANSLATE(str, from, to) - Character-by-character replacement</li>
 * </ul>
 *
 * <p>These functions have identical or nearly identical syntax in Oracle and PostgreSQL,
 * so they mostly pass through unchanged.
 */
public class StringFunctionBasicTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== LPAD Tests ====================

    @Test
    void lpadTwoArgs() {
        // Given: LPAD with string and length only (default pad is space)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD(emp_name, 20) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LPAD should pass through unchanged
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("LPAD( emp_name , 20 )"),
            "LPAD should pass through unchanged, got: " + normalized);
    }

    @Test
    void lpadThreeArgs() {
        // Given: LPAD with string, length, and pad character
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD(emp_id, 10, '0') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LPAD should pass through unchanged
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("LPAD( emp_id , 10 , '0' )"),
            "LPAD with pad char should pass through unchanged, got: " + normalized);
    }

    @Test
    void lpadWithLiteral() {
        // Given: LPAD with string literal
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD('ABC', 5, '*') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LPAD should pass through
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("LPAD( 'ABC' , 5 , '*' )"),
            "LPAD with literals should pass through, got: " + normalized);
    }

    @Test
    void lpadInWhereClause() {
        // Given: LPAD in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE LPAD(emp_id, 5, '0') = '00123'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LPAD should work in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE LPAD( emp_id , 5 , '0' ) = '00123'"),
            "LPAD in WHERE should work, got: " + normalized);
    }

    @Test
    void lpadNested() {
        // Given: Nested LPAD calls
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD(LPAD(emp_id, 5, '0'), 10, '*') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Nested LPAD should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        int lpadCount = normalized.split("LPAD\\(", -1).length - 1;
        assertEquals(2, lpadCount,
            "Should have 2 LPAD calls for nested LPAD, got: " + normalized);
    }

    // ==================== RPAD Tests ====================

    @Test
    void rpadTwoArgs() {
        // Given: RPAD with string and length only (default pad is space)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT RPAD(emp_name, 20) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RPAD should pass through unchanged
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("RPAD( emp_name , 20 )"),
            "RPAD should pass through unchanged, got: " + normalized);
    }

    @Test
    void rpadThreeArgs() {
        // Given: RPAD with string, length, and pad character
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT RPAD(emp_name, 20, '.') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RPAD should pass through unchanged
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("RPAD( emp_name , 20 , '.' )"),
            "RPAD with pad char should pass through unchanged, got: " + normalized);
    }

    @Test
    void rpadWithLiteral() {
        // Given: RPAD with string literal
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT RPAD('XYZ', 8, '-') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RPAD should pass through
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("RPAD( 'XYZ' , 8 , '-' )"),
            "RPAD with literals should pass through, got: " + normalized);
    }

    @Test
    void lpadAndRpadTogether() {
        // Given: Both LPAD and RPAD in same query
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD(first_name, 10, ' '), RPAD(last_name, 15, ' ') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("LPAD( first_name , 10 , ' ' )"),
            "LPAD should work, got: " + normalized);
        assertTrue(normalized.contains("RPAD( last_name , 15 , ' ' )"),
            "RPAD should work, got: " + normalized);
    }

    // ==================== TRANSLATE Tests ====================

    @Test
    void translateBasic() {
        // Given: TRANSLATE for character replacement
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRANSLATE(phone, '()-', '   ') FROM contacts";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRANSLATE should pass through unchanged (identical syntax)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("TRANSLATE( phone , '()-' , ' ' )"),
            "TRANSLATE should pass through unchanged, got: " + normalized);
    }

    @Test
    void translateRemoveCharacters() {
        // Given: TRANSLATE to remove characters (Oracle idiom)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        // Note: In Oracle, if 'to' is shorter than 'from', extra 'from' chars are removed
        String oracleSql = "SELECT TRANSLATE(phone, '0123456789()-. ', '0123456789') FROM contacts";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRANSLATE should pass through
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("TRANSLATE( phone , '0123456789()-. ' , '0123456789' )"),
            "TRANSLATE for removal should pass through, got: " + normalized);
    }

    @Test
    void translateWithLiteral() {
        // Given: TRANSLATE with string literal
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRANSLATE('Hello World', 'aeiou', '12345') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRANSLATE should pass through
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("TRANSLATE( 'Hello World' , 'aeiou' , '12345' )"),
            "TRANSLATE with literals should pass through, got: " + normalized);
    }

    @Test
    void translateInWhereClause() {
        // Given: TRANSLATE in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE TRANSLATE(status, 'YN', '10') = '1'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRANSLATE in WHERE should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE TRANSLATE( status , 'YN' , '10' ) = '1'"),
            "TRANSLATE in WHERE should work, got: " + normalized);
    }

    @Test
    void translateNested() {
        // Given: Nested TRANSLATE calls
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRANSLATE(TRANSLATE(code, 'ABC', 'XYZ'), '123', '890') FROM data";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Nested TRANSLATE should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        int translateCount = normalized.split("TRANSLATE\\(", -1).length - 1;
        assertEquals(2, translateCount,
            "Should have 2 TRANSLATE calls for nested TRANSLATE, got: " + normalized);
    }

    // ==================== Mixed String Functions ====================

    @Test
    void mixedStringFunctions() {
        // Given: Multiple string functions in same query
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD(emp_id, 5, '0'), " +
                          "RPAD(emp_name, 20, ' '), " +
                          "TRANSLATE(phone, '()-', '   ') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All functions should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("LPAD( emp_id , 5 , '0' )"),
            "LPAD should work, got: " + normalized);
        assertTrue(normalized.contains("RPAD( emp_name , 20 , ' ' )"),
            "RPAD should work, got: " + normalized);
        assertTrue(normalized.contains("TRANSLATE( phone , '()-' , ' ' )"),
            "TRANSLATE should work, got: " + normalized);
    }

    @Test
    void stringFunctionsWithInstr() {
        // Given: Mix of string functions including INSTR
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LPAD(emp_id, INSTR(emp_id, '-') + 5, '0') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both LPAD and INSTR should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("LPAD("),
            "LPAD should be present, got: " + normalized);
        assertTrue(normalized.contains("POSITION( '-' IN emp_id )"),
            "INSTR should be transformed to POSITION, got: " + normalized);
    }
}
