package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for expression building blocks:
 * - Literals (strings, numbers, NULL, booleans)
 * - Logical operators (AND, OR, NOT)
 * - IS NULL / IS NOT NULL
 * - Parenthesized expressions
 * - IN, BETWEEN, LIKE operators
 */
class ExpressionBuildingBlocksTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        builder = new PostgresCodeBuilder();
    }

    // ========== LITERAL TESTS ==========

    @Test
    void testSelectWithStringLiteral() {
        String oracleSql = "SELECT 'hello' FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT 'hello' FROM employees", normalized);
    }

    @Test
    void testSelectWithNumberLiteral() {
        String oracleSql = "SELECT 42, 3.14 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("42"));
        assertTrue(postgresSql.contains("3.14"));
    }

    @Test
    void testSelectWithNull() {
        String oracleSql = "SELECT NULL FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT NULL FROM employees", normalized);
    }

    @Test
    void testSelectWithBoolean() {
        String oracleSql = "SELECT TRUE, FALSE FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("TRUE"));
        assertTrue(postgresSql.contains("FALSE"));
    }

    // ========== LOGICAL OPERATOR TESTS ==========

    @Test
    void testSelectWithAnd() {
        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 AND sal > 1000";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("AND"));
        assertTrue(postgresSql.contains("deptno"));
        assertTrue(postgresSql.contains("sal"));
    }

    @Test
    void testSelectWithOr() {
        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 OR deptno = 20";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("OR"));
        assertTrue(postgresSql.contains("deptno"));
    }

    @Test
    void testSelectWithNot() {
        String oracleSql = "SELECT empno FROM employees WHERE NOT deptno = 10";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("NOT"));
    }

    @Test
    void testSelectWithComplexLogic() {
        String oracleSql = "SELECT empno FROM employees WHERE (deptno = 10 OR deptno = 20) AND sal > 1000";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("OR"));
        assertTrue(postgresSql.contains("AND"));
        assertTrue(postgresSql.contains("("));
        assertTrue(postgresSql.contains(")"));
    }

    // ========== IS NULL / IS NOT NULL TESTS ==========

    @Test
    void testSelectWithIsNull() {
        String oracleSql = "SELECT empno FROM employees WHERE commission IS NULL";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("IS NULL"));
    }

    @Test
    void testSelectWithIsNotNull() {
        String oracleSql = "SELECT empno FROM employees WHERE commission IS NOT NULL";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("IS NOT NULL"));
    }

    // ========== PARENTHESIZED EXPRESSION TESTS ==========

    @Test
    void testSelectWithParenthesizedExpression() {
        String oracleSql = "SELECT (sal * 12) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains("("));
        assertTrue(postgresSql.contains(")"));
        assertTrue(postgresSql.contains("sal"));
    }

    // ========== IN OPERATOR TESTS ==========

    @Test
    void testSelectWithInList() {
        String oracleSql = "SELECT empno FROM employees WHERE deptno IN (10, 20, 30)";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" IN "));
        assertTrue(postgresSql.contains("10"));
        assertTrue(postgresSql.contains("20"));
        assertTrue(postgresSql.contains("30"));
    }

    @Test
    void testSelectWithNotIn() {
        String oracleSql = "SELECT empno FROM employees WHERE deptno NOT IN (10, 20)";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" NOT IN "));
    }

    // ========== BETWEEN OPERATOR TESTS ==========

    @Test
    void testSelectWithBetween() {
        String oracleSql = "SELECT empno FROM employees WHERE sal BETWEEN 1000 AND 2000";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" BETWEEN "));
        assertTrue(postgresSql.contains("1000"));
        assertTrue(postgresSql.contains("2000"));
    }

    @Test
    void testSelectWithNotBetween() {
        String oracleSql = "SELECT empno FROM employees WHERE sal NOT BETWEEN 1000 AND 2000";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" NOT BETWEEN "));
    }

    // ========== LIKE OPERATOR TESTS ==========

    @Test
    void testSelectWithLike() {
        String oracleSql = "SELECT empno FROM employees WHERE ename LIKE 'S%'";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" LIKE "));
        assertTrue(postgresSql.contains("'S%'"));
    }

    @Test
    void testSelectWithNotLike() {
        String oracleSql = "SELECT empno FROM employees WHERE ename NOT LIKE 'S%'";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" NOT LIKE "));
    }

    @Test
    void testSelectWithLikeEscape() {
        String oracleSql = "SELECT empno FROM employees WHERE ename LIKE 'S\\_%' ESCAPE '\\'";
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors());

        String postgresSql = builder.visit(parseResult.getTree());
        assertTrue(postgresSql.contains(" LIKE "));
        assertTrue(postgresSql.contains(" ESCAPE "));
    }

    // ========== COMBINED TESTS ==========

    @Test
    void testComplexWhereClause() {
        String oracleSql = "SELECT empno, ename FROM employees " +
                "WHERE (deptno = 10 OR deptno = 20) " +
                "AND sal BETWEEN 1000 AND 5000 " +
                "AND commission IS NOT NULL " +
                "AND ename LIKE 'S%'";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());

        // Verify all operators are present
        assertTrue(postgresSql.contains("OR"));
        assertTrue(postgresSql.contains("AND"));
        assertTrue(postgresSql.contains("BETWEEN"));
        assertTrue(postgresSql.contains("IS NOT NULL"));
        assertTrue(postgresSql.contains("LIKE"));
    }

    @Test
    void testMultipleConditionsWithDifferentOperators() {
        String oracleSql = "SELECT * FROM employees " +
                "WHERE deptno IN (10, 20, 30) " +
                "AND sal > 1000 " +
                "AND (ename LIKE 'A%' OR ename LIKE 'B%') " +
                "AND commission IS NULL";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        String postgresSql = builder.visit(parseResult.getTree());
        assertNotNull(postgresSql);

        // Basic validation - check key keywords are present
        assertTrue(postgresSql.contains("IN"));
        assertTrue(postgresSql.contains("AND"));
        assertTrue(postgresSql.contains("OR"));
        assertTrue(postgresSql.contains("LIKE"));
        assertTrue(postgresSql.contains("IS NULL"));
    }
}
