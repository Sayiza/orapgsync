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
 * Comprehensive test to identify which subquery locations are already working
 * and which need implementation.
 *
 * <p>Subqueries can appear in multiple locations in SQL:
 * <ul>
 *   <li>FROM clause (inline views) - ALREADY IMPLEMENTED ✅</li>
 *   <li>SELECT list (scalar subqueries) - TO TEST</li>
 *   <li>WHERE clause with IN - TO TEST</li>
 *   <li>WHERE clause with EXISTS - TO TEST</li>
 *   <li>WHERE clause with comparison operators - TO TEST</li>
 *   <li>WHERE clause with ANY/ALL - TO TEST</li>
 * </ul>
 */
class SubqueryComprehensiveTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== FROM CLAUSE SUBQUERY (ALREADY IMPLEMENTED) ==========

    @Test
    void fromClauseSubquery() {
        // Given: Subquery in FROM clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT d.dept_id FROM (SELECT dept_id FROM departments) d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("FROM clause subquery - Parse errors: " + parseResult.hasErrors());

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());
        System.out.println("FROM clause subquery Oracle: " + oracleSql);
        System.out.println("FROM clause subquery Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
        System.out.println();

        // Then: Should work (already implemented)
        assertFalse(parseResult.hasErrors(), "FROM clause subquery should parse");
        assertTrue(postgresSql.contains("hr.departments"), "Should have schema qualification");
    }

    // ========== SELECT LIST SUBQUERY (SCALAR SUBQUERY) ==========

    @Test
    void scalarSubqueryInSelectList() {
        // Given: Scalar subquery in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno, (SELECT dname FROM departments WHERE deptno = emp.deptno) AS dept_name FROM employees emp";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("SELECT list scalar subquery - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("SELECT list scalar Oracle: " + oracleSql);
            System.out.println("SELECT list scalar Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "SELECT list subquery should parse");
    }

    // ========== WHERE CLAUSE - IN SUBQUERY ==========

    @Test
    void whereClauseInSubquery() {
        // Given: IN subquery in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE deptno IN (SELECT deptno FROM departments WHERE location = 'NY')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE IN subquery - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE IN Oracle: " + oracleSql);
            System.out.println("WHERE IN Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE IN subquery should parse");
    }

    @Test
    void whereClauseNotInSubquery() {
        // Given: NOT IN subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE deptno NOT IN (SELECT deptno FROM departments WHERE active = 'N')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE NOT IN subquery - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE NOT IN Oracle: " + oracleSql);
            System.out.println("WHERE NOT IN Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE NOT IN subquery should parse");
    }

    // ========== WHERE CLAUSE - EXISTS ==========

    @Test
    void whereClauseExistsSubquery() {
        // Given: EXISTS subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees e WHERE EXISTS (SELECT 1 FROM departments d WHERE d.deptno = e.deptno)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE EXISTS - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE EXISTS Oracle: " + oracleSql);
            System.out.println("WHERE EXISTS Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE EXISTS subquery should parse");
    }

    @Test
    void whereClauseNotExistsSubquery() {
        // Given: NOT EXISTS subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees e WHERE NOT EXISTS (SELECT 1 FROM salaries s WHERE s.empno = e.empno)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE NOT EXISTS - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE NOT EXISTS Oracle: " + oracleSql);
            System.out.println("WHERE NOT EXISTS Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE NOT EXISTS subquery should parse");
    }

    // ========== WHERE CLAUSE - COMPARISON WITH SCALAR SUBQUERY ==========

    @Test
    void whereClauseScalarSubquery() {
        // Given: Scalar subquery in comparison
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE salary > (SELECT AVG(salary) FROM employees)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE scalar comparison - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE scalar Oracle: " + oracleSql);
            System.out.println("WHERE scalar Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE scalar subquery should parse");
    }

    // ========== WHERE CLAUSE - ANY/ALL ==========

    @Test
    void whereClauseAnySubquery() {
        // Given: ANY subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE salary > ANY (SELECT salary FROM employees WHERE deptno = 10)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE ANY - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE ANY Oracle: " + oracleSql);
            System.out.println("WHERE ANY Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE ANY subquery should parse");
    }

    @Test
    void whereClauseAllSubquery() {
        // Given: ALL subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE salary > ALL (SELECT salary FROM employees WHERE deptno = 10)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        System.out.println("WHERE ALL - Parse errors: " + parseResult.hasErrors());

        if (!parseResult.hasErrors()) {
            PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
            String postgresSql = builder.visit(parseResult.getTree());
            System.out.println("WHERE ALL Oracle: " + oracleSql);
            System.out.println("WHERE ALL Result:  " + postgresSql.trim().replaceAll("\\s+", " "));
            System.out.println();
        }

        // Then: Check if it works
        assertFalse(parseResult.hasErrors(), "WHERE ALL subquery should parse");
    }
}
