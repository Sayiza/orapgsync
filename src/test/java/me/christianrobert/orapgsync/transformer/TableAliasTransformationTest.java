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
 * Tests for qualified column references using table aliases.
 *
 * <p>Oracle: SELECT e.empno, e.ename FROM employees e
 * <p>PostgreSQL: SELECT e.empno, e.ename FROM employees e
 *
 * <p>These should pass through unchanged (alias.column preserved).
 */
class TableAliasTransformationTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Create empty indices (no metadata needed for these tests)
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== BASIC TABLE ALIAS TESTS ==========

    @Test
    void selectWithSingleQualifiedColumn() {
        // Given: Query with table alias and qualified column
        String oracleSql = "SELECT e.empno FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified column preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . empno FROM employees e", normalized,
                "Qualified column should be preserved");
    }

    @Test
    void selectWithMultipleQualifiedColumns() {
        // Given: Query with multiple qualified columns
        String oracleSql = "SELECT e.empno, e.ename, e.sal FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All qualified columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . empno , e . ename , e . sal FROM employees e", normalized,
                "All qualified columns should be preserved");
    }

    @Test
    void selectMixingQualifiedAndUnqualifiedColumns() {
        // Given: Query mixing qualified and unqualified columns
        String oracleSql = "SELECT empno, e.ename, sal FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified preserved, unqualified preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , e . ename , sal FROM employees e", normalized,
                "Mixed qualified/unqualified columns should be preserved correctly");
    }

    @Test
    void selectWithUppercaseAlias() {
        // Given: Query with uppercase alias
        String oracleSql = "SELECT E.EMPNO, E.ENAME FROM EMPLOYEES E";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Case preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT E . EMPNO , E . ENAME FROM EMPLOYEES E", normalized,
                "Uppercase aliases should be preserved");
    }

    @Test
    void selectWithMixedCaseAlias() {
        // Given: Query with mixed case alias
        String oracleSql = "SELECT Emp.EmpNo, Emp.EmpName FROM Employees Emp";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Mixed case preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT Emp . EmpNo , Emp . EmpName FROM Employees Emp", normalized,
                "Mixed case should be preserved");
    }

    // ========== MULTIPLE TABLE ALIASES ==========

    // TODO: Enable when multiple tables in FROM clause are supported
    // @Test
    // void selectFromMultipleTablesWithAliases() {
    //     // Given: Query with multiple tables and aliases (cross join for simplicity)
    //     String oracleSql = "SELECT e.empno, d.dname FROM employees e, departments d";
    //
    //     // When: Parse and transform
    //     ParseResult parseResult = parser.parseSelectStatement(oracleSql);
    //     assertFalse(parseResult.hasErrors(), "Parse should succeed");
    //
    //     builder = new PostgresCodeBuilder();
    //     String postgresSql = builder.visit(parseResult.getTree());
    //
    //     // Then: Both qualified columns preserved
    //     String normalized = postgresSql.trim().replaceAll("\\s+", " ");
    //     assertTrue(normalized.contains("e . empno"), "Should contain e.empno");
    //     assertTrue(normalized.contains("d . dname"), "Should contain d.dname");
    // }

    // ========== WITH CONTEXT ==========

    @Test
    void selectWithQualifiedColumnsAndContext() {
        // Given: Query with qualified columns and transformation context
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT e.empno, e.ename FROM employees e";

        // When: Parse and transform with context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified columns still preserved (context doesn't affect column references)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT e . empno , e . ename FROM employees e", normalized,
                "Qualified columns should work with context");
    }

    // ========== EDGE CASES ==========

    @Test
    void selectWithLongAlias() {
        // Given: Query with longer alias name
        String oracleSql = "SELECT emp_table.empno FROM employees emp_table";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Long alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT emp_table . empno FROM employees emp_table", normalized,
                "Long aliases should be preserved");
    }

    @Test
    void selectWithAliasContainingUnderscore() {
        // Given: Query with alias containing underscore
        String oracleSql = "SELECT emp_t.emp_no FROM employee_table emp_t";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Underscore alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT emp_t . emp_no FROM employee_table emp_t", normalized,
                "Aliases with underscores should be preserved");
    }

    @Test
    void selectWithNoAlias() {
        // Given: Query without alias (unqualified columns only)
        String oracleSql = "SELECT empno, ename FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Unqualified columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT empno , ename FROM employees", normalized,
                "Unqualified columns should work without alias");
    }
}
