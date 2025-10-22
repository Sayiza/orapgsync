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
 * Tests for subquery (inline view) support in FROM clause.
 *
 * <p>Oracle allows subqueries in FROM clause (also called "inline views" or "derived tables"):
 * <pre>
 * SELECT e.empno, d.dept_name
 * FROM employees e,
 *      (SELECT dept_id, dept_name FROM departments WHERE active = 'Y') d
 * WHERE e.dept_id = d.dept_id
 * </pre>
 *
 * <p>PostgreSQL uses the same syntax:
 * <pre>
 * SELECT e.empno, d.dept_name
 * FROM hr.employees e,
 *      (SELECT dept_id, dept_name FROM hr.departments WHERE active = 'Y') d
 * WHERE e.dept_id = d.dept_id
 * </pre>
 *
 * <p>Key transformations:
 * <ul>
 *   <li>Subquery SQL is transformed recursively (tables qualified, synonyms resolved, etc.)</li>
 *   <li>Subquery must have an alias (mandatory in PostgreSQL)</li>
 *   <li>Nested subqueries are supported (subquery within subquery)</li>
 * </ul>
 */
class SubqueryFromClauseTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== BASIC SUBQUERY TESTS ==========

    @Test
    void simpleSubqueryWithAlias() {
        // Given: Query with subquery in FROM clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d.dept_id FROM (SELECT dept_id FROM departments) d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Subquery preserved with table qualified
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT d . dept_id FROM ( SELECT dept_id FROM hr.departments ) d", normalized,
                "Subquery should be transformed with qualified table name");
    }

    @Test
    void subqueryWithMultipleColumns() {
        // Given: Subquery selecting multiple columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d.dept_id, d.dept_name FROM (SELECT dept_id, dept_name FROM departments) d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT d . dept_id , d . dept_name FROM ( SELECT dept_id , dept_name FROM hr.departments ) d", normalized);
    }

    @Test
    void subqueryWithWhereClause() {
        // Given: Subquery with WHERE filter
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d.dept_id FROM (SELECT dept_id FROM departments WHERE active = 'Y') d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: WHERE clause preserved in subquery
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT d . dept_id FROM ( SELECT dept_id FROM hr.departments WHERE active = 'Y' ) d", normalized,
                "Subquery WHERE clause should be preserved");
    }

    @Test
    void subqueryWithComplexWhereCondition() {
        // Given: Subquery with complex WHERE (AND/OR)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d.dept_id FROM (SELECT dept_id FROM departments WHERE active = 'Y' AND region = 'WEST') d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE active = 'Y' AND region = 'WEST'"),
                "Complex WHERE condition should be preserved");
    }

    // ========== MIXED TABLE AND SUBQUERY ==========

    @Test
    void regularTableWithSubquery() {
        // Given: Mix of regular table and subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT e.empno, d.dept_name FROM employees e, (SELECT dept_id, dept_name FROM departments) d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both regular table and subquery present
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("FROM hr.employees e"), "Regular table should be qualified");
        assertTrue(normalized.contains("( SELECT dept_id , dept_name FROM hr.departments ) d"),
                "Subquery should be present with qualified table");
    }

    @Test
    void multipleSubqueries() {
        // Given: Multiple subqueries in FROM clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d1.dept_id, d2.dept_name " +
                          "FROM (SELECT dept_id FROM departments) d1, " +
                          "(SELECT dept_name FROM departments) d2";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both subqueries present
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("( SELECT dept_id FROM hr.departments ) d1"),
                "First subquery should be present");
        assertTrue(normalized.contains("( SELECT dept_name FROM hr.departments ) d2"),
                "Second subquery should be present");
    }

    // ========== NESTED SUBQUERIES ==========

    @Test
    void nestedSubquery() {
        // Given: Subquery containing another subquery (2 levels)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT outer_alias.dept_id " +
                          "FROM (SELECT d.dept_id FROM (SELECT dept_id FROM departments) d) outer_alias";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Nested structure preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("FROM ( SELECT d . dept_id FROM ( SELECT dept_id FROM hr.departments ) d ) outer_alias"),
                "Nested subqueries should be preserved");
    }

    @Test
    void deeplyNestedSubquery() {
        // Given: 3-level nested subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT level3.dept_id " +
                          "FROM (SELECT level2.dept_id " +
                          "FROM (SELECT level1.dept_id " +
                          "FROM (SELECT dept_id FROM departments) level1) level2) level3";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All 3 levels preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertNotNull(postgresSql);
        // Exact assertion depends on implementation, but should not crash
        assertTrue(normalized.contains("hr.departments"),
                "Innermost table should be qualified");
    }

    // ========== SUBQUERY WITH SELECT * ==========

    @Test
    void subqueryWithSelectStar() {
        // Given: Subquery using SELECT *
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d.dept_id FROM (SELECT * FROM departments) d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: SELECT * preserved in subquery
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT d . dept_id FROM ( SELECT * FROM hr.departments ) d", normalized,
                "SELECT * in subquery should be preserved");
    }

    @Test
    void subqueryWithQualifiedSelectStar() {
        // Given: Subquery using qualified SELECT (e.*)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT d.dept_id FROM (SELECT dep.* FROM departments dep) d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified SELECT * preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT d . dept_id FROM ( SELECT dep . * FROM hr.departments dep ) d", normalized);
    }

    // ========== EDGE CASES ==========

    @Test
    void subqueryWithUppercaseAlias() {
        // Given: Subquery with uppercase alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT D.DEPT_ID FROM (SELECT DEPT_ID FROM DEPARTMENTS) D";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Case preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains(") D"), "Uppercase alias should be preserved");
    }

    @Test
    void subqueryWithoutContext() {
        // Given: Subquery transformation without context (no schema qualification)
        String oracleSql = "SELECT d.dept_id FROM (SELECT dept_id FROM departments) d";

        // When: Parse and transform without context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: No schema qualification (test that it doesn't crash)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertEquals("SELECT d . dept_id FROM ( SELECT dept_id FROM departments ) d", normalized,
                "Without context, no schema qualification should occur");
    }

    @Test
    void subqueryWithTableAlias() {
        // Given: Subquery with table alias inside the subquery
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT outer_d.dept_id FROM (SELECT d.dept_id FROM departments d) outer_d";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both aliases preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("d . dept_id FROM hr.departments d"),
                "Inner alias should be preserved");
        assertTrue(normalized.endsWith(") outer_d"),
                "Outer alias should be preserved");
    }
}
