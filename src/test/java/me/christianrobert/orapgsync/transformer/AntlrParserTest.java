package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ANTLR parser and direct AST-to-code transformation.
 * Tests the complete pipeline: SQL → ANTLR parse tree → PostgreSQL SQL (direct)
 *
 * This is the direct AST transformation approach (no intermediate semantic tree).
 */
class AntlrParserTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        builder = new PostgresCodeBuilder();
    }

    @Test
    void parseSimpleSingleColumnSelect() {
        String sql = "SELECT empno FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);

        assertTrue(parseResult.isSuccess(), "Parsing should succeed");
        assertFalse(parseResult.hasErrors(), "Should have no errors");
        assertNotNull(parseResult.getTree(), "Parse tree should not be null");
    }

    @Test
    void parseSimpleTwoColumnSelect() {
        String sql = "SELECT empno, ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);

        assertTrue(parseResult.isSuccess());
        assertFalse(parseResult.hasErrors());
    }

    @Test
    void parseSelectWithAlias() {
        String sql = "SELECT empno FROM employees e";

        ParseResult parseResult = parser.parseSelectStatement(sql);

        assertTrue(parseResult.isSuccess());
    }

    @Test
    void fullTransformationPipelineSingleColumn() {
        String oracleSql = "SELECT empno FROM emp";
        String expectedPostgres = "SELECT empno FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        assertNotNull(postgresSql, "PostgreSQL SQL should not be null");

        // Normalize whitespace for comparison
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void fullTransformationPipelineMultiColumn() {
        String oracleSql = "SELECT empno, ename FROM emp";
        String expectedPostgres = "SELECT empno , ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void fullTransformationPipelineThreeColumns() {
        String oracleSql = "SELECT empno, ename, sal FROM employees";
        String expectedPostgres = "SELECT empno , ename , sal FROM employees";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void fullTransformationPipelineWithAlias() {
        String oracleSql = "SELECT empno FROM employees e";
        String expectedPostgres = "SELECT empno FROM employees e";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void nullSqlThrowsException() {
        assertThrows(TransformationException.class, () -> parser.parseSelectStatement(null));
    }

    @Test
    void emptySqlThrowsException() {
        assertThrows(TransformationException.class, () -> parser.parseSelectStatement(""));
    }

    @Test
    void whitespaceOnlySqlThrowsException() {
        assertThrows(TransformationException.class, () -> parser.parseSelectStatement("   "));
    }

    @Test
    void parseResultContainsOriginalSql() {
        String sql = "SELECT empno FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);

        assertEquals(sql, parseResult.getOriginalSql());
    }

    @Test
    void selectWithWhitespaceVariations() {
        String sql = "SELECT   empno  ,  ename   FROM   emp";
        String expectedPostgres = "SELECT empno , ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void selectWithNewlines() {
        String sql = "SELECT empno,\n       ename\nFROM emp";
        String expectedPostgres = "SELECT empno , ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void selectWithUppercaseKeywords() {
        String sql = "SELECT EMPNO, ENAME FROM EMP";
        String expectedPostgres = "SELECT EMPNO , ENAME FROM EMP";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }

    @Test
    void selectWithLowercaseKeywords() {
        String sql = "select empno, ename from emp";
        String expectedPostgres = "SELECT empno , ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        String postgresSql = builder.visit(parseResult.getTree());

        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals(expectedPostgres, normalized);
    }
}
