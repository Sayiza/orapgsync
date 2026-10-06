package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for column alias transformations.
 *
 * <p>Oracle supports column aliases with or without AS keyword:
 * <ul>
 *   <li>AS alias_name - explicit AS keyword</li>
 *   <li>alias_name - implicit (no AS keyword)</li>
 *   <li>AS "Quoted Alias" - quoted alias with AS</li>
 *   <li>"Quoted Alias" - quoted alias without AS</li>
 * </ul>
 *
 * <p>PostgreSQL supports the same syntax. Our transformation always outputs
 * AS keyword for clarity and consistency.
 */
public class ColumnAliasTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== Simple Column Aliases ====================

    @Test
    void simpleColumnWithExplicitAs() {
        // Given: Column with explicit AS keyword
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno AS employee_number FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Alias should be preserved with AS keyword
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS employee_number"),
            "Should contain alias with AS keyword");
        assertTrue(normalized.contains("FROM hr.employees"),
            "Table should be schema-qualified");
    }

    @Test
    void simpleColumnWithImplicitAs() {
        // Given: Column with implicit alias (no AS keyword in Oracle)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno employee_number FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Alias should be output with AS keyword for clarity
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS employee_number"),
            "Should add AS keyword for clarity");
    }

    @Test
    void quotedAlias() {
        // Given: Column with quoted alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno AS \"Employee Number\" FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Quoted alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS \"Employee Number\""),
            "Should preserve quoted alias");
    }

    @Test
    void multipleColumnsWithAliases() {
        // Given: Multiple columns with aliases
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno AS id, ename AS name, sal AS salary FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All aliases should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS id"), "Should have empno alias");
        assertTrue(normalized.contains("ename AS name"), "Should have ename alias");
        assertTrue(normalized.contains("sal AS salary"), "Should have sal alias");
    }

    @Test
    void mixedAliasedAndNonAliasedColumns() {
        // Given: Mix of aliased and non-aliased columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename AS name, sal FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Only aliased column should have AS
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno ,"), "empno should not have alias");
        assertTrue(normalized.contains("ename AS name"), "ename should have alias");
        assertTrue(normalized.contains("sal FROM"), "sal should not have alias");
    }

    // ==================== Expression Aliases ====================

    @Test
    void arithmeticExpressionWithAlias() {
        // Given: Arithmetic expression with alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT salary * 12 AS annual_salary FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Expression alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary * 12 AS annual_salary"),
            "Arithmetic expression should have alias");
    }

    @Test
    void functionCallWithAlias() {
        // Given: Function call with alias (using NVL which transforms to COALESCE)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT NVL(commission, 0) AS comm FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Function alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COALESCE( commission , 0 ) AS comm"),
            "Function call should have alias");
    }

    @Test
    void stringConcatenationWithAlias() {
        // Given: String concatenation with alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT first_name || ' ' || last_name AS full_name FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Concatenation alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("AS full_name"),
            "String concatenation should have alias");
        assertTrue(normalized.contains("CONCAT("),
            "Should use CONCAT for NULL-safe concatenation");
    }

    @Test
    void caseExpressionWithAlias() {
        // Given: DECODE (transformed to CASE) with alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT DECODE(deptno, 10, 'Sales', 20, 'IT', 'Other') AS dept_name FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CASE expression should have alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CASE deptno"),
            "DECODE should be transformed to CASE");
        assertTrue(normalized.contains("END AS dept_name"),
            "CASE expression should have alias");
    }

    @Test
    void aggregateFunctionWithAlias() {
        // Given: Aggregate function with alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT COUNT(*) AS emp_count FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Aggregate function alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("COUNT( * ) AS emp_count"),
            "COUNT should have alias");
    }

    // ==================== Aliases in Different Clauses ====================

    @Test
    void aliasInSelectWithWhere() {
        // Given: Alias in SELECT with WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno AS id, ename AS name FROM employees WHERE deptno = 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Aliases should be preserved, WHERE should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS id"), "Should have empno alias");
        assertTrue(normalized.contains("ename AS name"), "Should have ename alias");
        assertTrue(normalized.contains("WHERE deptno = 10"), "WHERE should be preserved");
    }

    @Test
    void aliasInSelectWithGroupBy() {
        // Given: Alias in SELECT with GROUP BY
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT deptno AS dept, COUNT(*) AS emp_count FROM employees GROUP BY deptno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Aliases should be preserved, GROUP BY should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("deptno AS dept"), "Should have deptno alias");
        assertTrue(normalized.contains("COUNT( * ) AS emp_count"), "Should have COUNT alias");
        assertTrue(normalized.contains("GROUP BY deptno"), "GROUP BY should be preserved");
    }

    @Test
    void aliasInSelectWithOrderBy() {
        // Given: Alias in SELECT with ORDER BY (referencing alias)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT salary * 12 AS annual_sal FROM employees ORDER BY annual_sal DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Alias should be preserved, ORDER BY should reference it
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary * 12 AS annual_sal"),
            "Should have expression alias");
        assertTrue(normalized.contains("ORDER BY annual_sal DESC NULLS FIRST"),
            "ORDER BY should reference alias");
    }

    // ==================== Edge Cases ====================

    @Test
    void selectStarWithoutAlias() {
        // Given: SELECT * has no alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT * FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT * should not have alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SELECT *"), "Should have SELECT *");
        assertFalse(normalized.contains(" AS "), "Should not have any aliases");
    }

    @Test
    void qualifiedSelectStarWithoutAlias() {
        // Given: SELECT e.* has no alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.* FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT e.* should not have alias
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("SELECT e . *"), "Should have qualified SELECT");
        assertFalse(normalized.contains(" AS "), "Should not have any aliases");
    }

    @Test
    void aliasWithReservedKeyword() {
        // Given: Alias using reserved keyword (should be quoted)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno AS \"SELECT\" FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Quoted alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS \"SELECT\""),
            "Should preserve quoted reserved keyword alias");
    }

    @Test
    void aliasWithSpecialCharacters() {
        // Given: Alias with special characters (requires quoting)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno AS \"Emp#\" FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Quoted alias with special chars should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno AS \"Emp#\""),
            "Should preserve quoted alias with special characters");
    }

    @Test
    void complexExpressionWithLongAlias() {
        // Given: Complex expression with descriptive alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT (salary * 12) + COALESCE(bonus, 0) AS total_annual_compensation FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex expression alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("AS total_annual_compensation"),
            "Should have descriptive alias for complex expression");
    }
}
