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
 * Tests for Oracle sequence pseudo-column transformations to PostgreSQL function calls.
 *
 * <p>This test class covers:
 * <ul>
 *   <li>NEXTVAL → nextval() transformation</li>
 *   <li>CURRVAL → currval() transformation</li>
 *   <li>Schema qualification and synonym resolution</li>
 *   <li>Case-insensitive detection</li>
 *   <li>Integration with various SQL contexts (SELECT, WHERE, INSERT, etc.)</li>
 * </ul>
 */
public class SequenceTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== Basic NEXTVAL Tests ====================

    @Test
    void nextvalSimple() {
        // Given: Simple sequence NEXTVAL in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.NEXTVAL FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed to nextval() with schema qualification
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed to nextval('hr.emp_seq')");
        assertTrue(normalized.contains("FROM hr.employees"), "Table should be schema-qualified");
        assertFalse(normalized.contains("NEXTVAL"), "NEXTVAL should not appear in output");
    }

    @Test
    void nextvalWithSchemaPrefix() {
        // Given: Sequence NEXTVAL with explicit schema prefix
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT sales.order_seq.NEXTVAL FROM orders";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Schema prefix should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('sales.order_seq')"),
            "Schema prefix should be preserved in nextval()");
    }

    @Test
    void nextvalCaseInsensitive() {
        // Given: NEXTVAL in various cases (nextval, NextVal, etc.)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.nextval FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Lowercase nextval should also be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "Lowercase nextval should be transformed");
    }

    // ==================== Basic CURRVAL Tests ====================

    @Test
    void currvalSimple() {
        // Given: Simple sequence CURRVAL in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.CURRVAL FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CURRVAL should be transformed to currval() with schema qualification
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("currval('hr.emp_seq')"),
            "CURRVAL should be transformed to currval('hr.emp_seq')");
        assertFalse(normalized.contains("CURRVAL"), "CURRVAL should not appear in output");
    }

    @Test
    void currvalWithSchemaPrefix() {
        // Given: Sequence CURRVAL with explicit schema prefix
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT sales.order_seq.CURRVAL FROM orders";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Schema prefix should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("currval('sales.order_seq')"),
            "Schema prefix should be preserved in currval()");
    }

    @Test
    void currvalCaseInsensitive() {
        // Given: CURRVAL in various cases
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.CurrVal FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Mixed case currval should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("currval('hr.emp_seq')"),
            "Mixed case CURRVAL should be transformed");
    }

    // ==================== Context Integration Tests ====================

    @Test
    void nextvalInWhereClause() {
        // Given: NEXTVAL used in WHERE clause comparison
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE emp_id = emp_seq.NEXTVAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE"), "WHERE clause should be present");
        assertTrue(normalized.contains("emp_id = nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed in WHERE clause");
    }

    @Test
    void nextvalWithColumnAlias() {
        // Given: NEXTVAL with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.NEXTVAL AS new_id FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed with alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed");
        assertTrue(normalized.contains("AS new_id"),
            "Column alias should be preserved");
    }

    @Test
    void multipleSequencesInSelectList() {
        // Given: Multiple sequence calls in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.NEXTVAL, dept_seq.NEXTVAL FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both sequences should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "First sequence should be transformed");
        assertTrue(normalized.contains("nextval('hr.dept_seq')"),
            "Second sequence should be transformed");
    }

    @Test
    void nextvalAndCurrvalMixed() {
        // Given: Mix of NEXTVAL and CURRVAL in same query
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.NEXTVAL, emp_seq.CURRVAL FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both NEXTVAL and CURRVAL should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed");
        assertTrue(normalized.contains("currval('hr.emp_seq')"),
            "CURRVAL should be transformed");
    }

    @Test
    void nextvalWithOtherColumns() {
        // Given: NEXTVAL mixed with regular columns
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno, emp_seq.NEXTVAL, ename FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed, other columns preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("empno"), "Regular column should be present");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed");
        assertTrue(normalized.contains("ename"), "Regular column should be present");
    }

    // ==================== FROM DUAL Integration Tests ====================

    @Test
    void nextvalFromDual() {
        // Given: NEXTVAL with FROM DUAL (common Oracle pattern)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.NEXTVAL FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed and FROM DUAL should be omitted
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed");
        assertFalse(normalized.contains("FROM"), "FROM DUAL should be omitted");
        assertEquals("SELECT nextval('hr.emp_seq')", normalized,
            "Should be standalone SELECT with nextval");
    }

    @Test
    void currvalFromDual() {
        // Given: CURRVAL with FROM DUAL
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.CURRVAL FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CURRVAL should be transformed and FROM DUAL should be omitted
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("currval('hr.emp_seq')"),
            "CURRVAL should be transformed");
        assertFalse(normalized.contains("FROM"), "FROM DUAL should be omitted");
    }

    @Test
    void nextvalFromDualWithSchemaPrefix() {
        // Given: Schema-qualified sequence with FROM DUAL
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT sales.order_seq.NEXTVAL FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Schema prefix should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('sales.order_seq')"),
            "Schema prefix should be preserved");
        assertFalse(normalized.contains("FROM"), "FROM DUAL should be omitted");
    }

    // ==================== Edge Case Tests ====================

    @Test
    void nextvalInOrderByClause() {
        // Given: NEXTVAL in ORDER BY (unusual but valid)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY emp_seq.NEXTVAL DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY"), "ORDER BY should be present");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed");
        assertTrue(normalized.contains("DESC NULLS FIRST"),
            "DESC should have NULLS FIRST");
    }

    @Test
    void currvalInGroupByClause() {
        // Given: CURRVAL in GROUP BY (unusual but valid)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT COUNT(*) FROM employees GROUP BY emp_seq.CURRVAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CURRVAL should be transformed in GROUP BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("GROUP BY"), "GROUP BY should be present");
        assertTrue(normalized.contains("currval('hr.emp_seq')"),
            "CURRVAL should be transformed");
    }

    @Test
    void nextvalWithArithmeticExpression() {
        // Given: NEXTVAL in arithmetic expression
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT emp_seq.NEXTVAL + 1000 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NEXTVAL should be transformed with arithmetic preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.emp_seq')"),
            "NEXTVAL should be transformed");
        assertTrue(normalized.contains("+ 1000"),
            "Arithmetic expression should be preserved");
    }

    @Test
    void sequenceNameWithUnderscores() {
        // Given: Sequence name with underscores (common naming pattern)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT employee_id_seq.NEXTVAL FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Underscores should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.employee_id_seq')"),
            "Underscores in sequence name should be preserved");
    }

    @Test
    void sequenceNameWithNumbers() {
        // Given: Sequence name with numbers
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT seq123.NEXTVAL FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numbers should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("nextval('hr.seq123')"),
            "Numbers in sequence name should be preserved");
    }
}
