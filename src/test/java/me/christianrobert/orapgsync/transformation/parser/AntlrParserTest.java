package me.christianrobert.orapgsync.transformation.parser;

import me.christianrobert.orapgsync.transformation.builder.SemanticTreeBuilder;
import me.christianrobert.orapgsync.transformation.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ANTLR parser and semantic tree builder.
 * Tests the complete pipeline: SQL → ANTLR parse tree → Semantic tree → PostgreSQL SQL
 */
class AntlrParserTest {

    private AntlrParser parser;
    private SemanticTreeBuilder builder;
    private TransformationContext context;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        builder = new SemanticTreeBuilder();
        context = new TransformationContext("test_schema", MetadataIndexBuilder.buildEmpty());
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
    void buildSemanticTreeFromSimpleSelect() {
        String sql = "SELECT empno FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        SemanticNode tree = builder.visit(parseResult.getTree());

        assertNotNull(tree);
        assertInstanceOf(SelectStatement.class, tree);
    }

    @Test
    void buildSemanticTreeFromMultiColumnSelect() {
        String sql = "SELECT empno, ename, sal FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        SemanticNode tree = builder.visit(parseResult.getTree());

        assertNotNull(tree);
        assertInstanceOf(SelectStatement.class, tree);

        SelectStatement select = (SelectStatement) tree;
        assertEquals(3, select.getSelectOnlyStatement().getSubquery().getBasicElements().getQueryBlock().getSelectedList().getElements().size());
    }

    @Test
    void fullTransformationPipelineSingleColumn() {
        String oracleSql = "SELECT empno FROM emp";
        String expectedPostgres = "SELECT empno FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertTrue(parseResult.isSuccess(), "Parsing should succeed");

        SemanticNode tree = builder.visit(parseResult.getTree());
        assertNotNull(tree, "Semantic tree should not be null");

        String postgresSql = tree.toPostgres(context);
        assertEquals(expectedPostgres, postgresSql);
    }

    @Test
    void fullTransformationPipelineMultiColumn() {
        String oracleSql = "SELECT empno, ename FROM emp";
        String expectedPostgres = "SELECT empno, ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
    }

    @Test
    void fullTransformationPipelineThreeColumns() {
        String oracleSql = "SELECT empno, ename, sal FROM employees";
        String expectedPostgres = "SELECT empno, ename, sal FROM employees";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
    }

    @Test
    void fullTransformationPipelineWithAlias() {
        String oracleSql = "SELECT empno FROM employees e";
        String expectedPostgres = "SELECT empno FROM employees e";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
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
        String expectedPostgres = "SELECT empno, ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
    }

    @Test
    void selectWithNewlines() {
        String sql = "SELECT empno,\n       ename\nFROM emp";
        String expectedPostgres = "SELECT empno, ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
    }

    @Test
    void selectWithUppercaseKeywords() {
        String sql = "SELECT EMPNO, ENAME FROM EMP";
        String expectedPostgres = "SELECT EMPNO, ENAME FROM EMP";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
    }

    @Test
    void selectWithLowercaseKeywords() {
        String sql = "select empno, ename from emp";
        String expectedPostgres = "SELECT empno, ename FROM emp";

        ParseResult parseResult = parser.parseSelectStatement(sql);
        SemanticNode tree = builder.visit(parseResult.getTree());
        String postgresSql = tree.toPostgres(context);

        assertEquals(expectedPostgres, postgresSql);
    }
}
