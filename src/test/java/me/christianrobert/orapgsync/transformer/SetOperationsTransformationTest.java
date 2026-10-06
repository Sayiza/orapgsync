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
 * Tests for set operations (UNION, INTERSECT, MINUS/EXCEPT) transformation.
 *
 * <p>Oracle and PostgreSQL set operation differences:
 * <ul>
 *   <li>UNION: Identical in both databases (removes duplicates)</li>
 *   <li>UNION ALL: Identical in both databases (keeps duplicates)</li>
 *   <li>INTERSECT: Identical in both databases (returns common rows)</li>
 *   <li>MINUS (Oracle) → EXCEPT (PostgreSQL) - keyword transformation required</li>
 * </ul>
 */
class SetOperationsTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== UNION ====================

    @Test
    void simpleUnion() {
        // Given: UNION without ALL
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "UNION " +
                          "SELECT empno FROM employees WHERE deptno = 20";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: UNION preserved (identical in both databases)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("UNION SELECT empno FROM hr.employees WHERE deptno = 20"),
                "UNION should be preserved");
        assertFalse(normalized.contains("UNION ALL"),
                "Should not have ALL modifier");
    }

    @Test
    void unionAll() {
        // Given: UNION ALL
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "UNION ALL " +
                          "SELECT empno FROM employees WHERE deptno = 20";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: UNION ALL preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("UNION ALL SELECT empno FROM hr.employees WHERE deptno = 20"),
                "UNION ALL should be preserved");
    }

    @Test
    void multipleUnions() {
        // Given: Multiple UNION operations
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "UNION " +
                          "SELECT empno FROM employees WHERE deptno = 20 " +
                          "UNION " +
                          "SELECT empno FROM employees WHERE deptno = 30";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All UNIONs preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        int unionCount = normalized.split(" UNION ", -1).length - 1;
        assertEquals(2, unionCount, "Should have 2 UNION operations");
    }

    // ==================== INTERSECT ====================

    @Test
    void simpleIntersect() {
        // Given: INTERSECT operation
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "INTERSECT " +
                          "SELECT empno FROM managers";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: INTERSECT preserved (identical in both databases)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("INTERSECT SELECT empno FROM hr.managers"),
                "INTERSECT should be preserved");
    }

    @Test
    void intersectWithWhereClause() {
        // Given: INTERSECT with WHERE clauses in both queries
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE salary > 50000 " +
                          "INTERSECT " +
                          "SELECT empno FROM employees WHERE deptno = 10";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both WHERE clauses preserved with INTERSECT
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE salary > 50000"),
                "First WHERE should be preserved");
        assertTrue(normalized.contains("INTERSECT"),
                "INTERSECT should be present");
        assertTrue(normalized.contains("WHERE deptno = 10"),
                "Second WHERE should be preserved");
    }

    // ==================== MINUS → EXCEPT ====================

    @Test
    void minusTransformedToExcept() {
        // Given: MINUS operation (Oracle-specific)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees " +
                          "MINUS " +
                          "SELECT empno FROM managers";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MINUS transformed to EXCEPT
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("EXCEPT SELECT empno FROM hr.managers"),
                "MINUS should be transformed to EXCEPT");
        assertFalse(normalized.contains("MINUS"),
                "Should not contain MINUS keyword");
    }

    @Test
    void minusWithWhereClause() {
        // Given: MINUS with WHERE clauses
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "MINUS " +
                          "SELECT empno FROM employees WHERE salary < 30000";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MINUS → EXCEPT with WHERE clauses preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE deptno = 10"),
                "First WHERE should be preserved");
        assertTrue(normalized.contains("EXCEPT"),
                "Should have EXCEPT");
        assertFalse(normalized.contains("MINUS"),
                "Should not have MINUS");
        assertTrue(normalized.contains("WHERE salary < 30000"),
                "Second WHERE should be preserved");
    }

    // ==================== MIXED SET OPERATIONS ====================

    @Test
    void mixedUnionAndIntersect() {
        // Given: Combination of UNION and INTERSECT
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "UNION " +
                          "SELECT empno FROM employees WHERE deptno = 20 " +
                          "INTERSECT " +
                          "SELECT empno FROM managers";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both operations preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("UNION"),
                "Should have UNION");
        assertTrue(normalized.contains("INTERSECT"),
                "Should have INTERSECT");
    }

    @Test
    void mixedUnionAndMinus() {
        // Given: Combination of UNION and MINUS
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "UNION " +
                          "SELECT empno FROM employees WHERE deptno = 20 " +
                          "MINUS " +
                          "SELECT empno FROM managers";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: UNION preserved, MINUS → EXCEPT
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("UNION"),
                "Should have UNION");
        assertTrue(normalized.contains("EXCEPT"),
                "MINUS should be transformed to EXCEPT");
        assertFalse(normalized.contains("MINUS"),
                "Should not have MINUS");
    }

    // ==================== SET OPERATIONS WITH MULTIPLE COLUMNS ====================

    @Test
    void unionWithMultipleColumns() {
        // Given: UNION with multiple columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename, deptno FROM employees WHERE deptno = 10 " +
                          "UNION " +
                          "SELECT empno, ename, deptno FROM employees WHERE deptno = 20";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All columns and UNION preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno , ename , deptno"),
                "All columns should be preserved");
        assertTrue(normalized.contains("UNION"),
                "UNION should be preserved");
    }

    @Test
    void minusWithMultipleColumns() {
        // Given: MINUS with multiple columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, ename FROM employees " +
                          "MINUS " +
                          "SELECT empno, ename FROM managers";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Columns preserved, MINUS → EXCEPT
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno , ename"),
                "All columns should be preserved");
        assertTrue(normalized.contains("EXCEPT"),
                "MINUS should be transformed to EXCEPT");
    }

    // ==================== SET OPERATIONS WITH ORDER BY ====================

    @Test
    void unionWithOrderBy() {
        // Given: UNION with ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE deptno = 10 " +
                          "UNION " +
                          "SELECT empno FROM employees WHERE deptno = 20 " +
                          "ORDER BY empno";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: UNION and ORDER BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("UNION"),
                "UNION should be preserved");
        assertTrue(normalized.contains("ORDER BY empno"),
                "ORDER BY should be preserved");
    }
}
