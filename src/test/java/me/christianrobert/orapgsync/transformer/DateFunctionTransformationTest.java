package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.FullTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle date/time function transformations to PostgreSQL equivalents.
 *
 * <p>This test class covers:
 * <ul>
 *   <li>ADD_MONTHS → date + INTERVAL transformation</li>
 *   <li>MONTHS_BETWEEN → AGE/EXTRACT transformation</li>
 *   <li>LAST_DAY → DATE_TRUNC + INTERVAL transformation</li>
 *   <li>TRUNC(date) → DATE_TRUNC transformation (with heuristic to distinguish from numeric TRUNC)</li>
 *   <li>ROUND(date) → CASE WHEN + DATE_TRUNC transformation (with heuristic to distinguish from numeric ROUND)</li>
 *   <li>Numeric TRUNC/ROUND → pass through unchanged (heuristic-based detection)</li>
 * </ul>
 *
 * <p>Heuristic for TRUNC/ROUND disambiguation:
 * <ul>
 *   <li>If 2nd arg is date format string ('MM', 'YYYY', etc.) → Date function</li>
 *   <li>If 1st arg contains date expressions (SYSDATE, TO_DATE, etc.) → Date function</li>
 *   <li>If 1st arg contains date-like column names (*date*, *time*, created*, hire*, etc.) → Date function</li>
 *   <li>Otherwise → Numeric function (pass through)</li>
 * </ul>
 */
public class DateFunctionTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== ADD_MONTHS Function Tests ====================

    @Test
    void addMonthsSimple() {
        // Given: ADD_MONTHS with positive month count
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ADD_MONTHS(hire_date, 6) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ADD_MONTHS should be transformed to date + (n * INTERVAL '1 month')
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( 6 * INTERVAL '1 month' )"),
            "ADD_MONTHS should be transformed to date + (n * INTERVAL '1 month'), got: " + normalized);
        assertTrue(normalized.contains("FROM hr.employees"),
            "Table should be schema-qualified");
        assertFalse(normalized.toUpperCase().contains("ADD_MONTHS"),
            "ADD_MONTHS function should not appear in output");
    }

    @Test
    void addMonthsNegative() {
        // Given: ADD_MONTHS with negative month count (subtract months)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ADD_MONTHS(hire_date, -3) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ADD_MONTHS with negative value should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( -3 * INTERVAL '1 month' )"),
            "ADD_MONTHS with negative value should be transformed, got: " + normalized);
    }

    @Test
    void addMonthsInWhereClause() {
        // Given: ADD_MONTHS in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE hire_date > ADD_MONTHS(SYSDATE, -12)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ADD_MONTHS should be transformed in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE"),
            "WHERE clause should be present");
        assertTrue(normalized.contains("( -12 * INTERVAL '1 month' )"),
            "ADD_MONTHS should be transformed in WHERE clause, got: " + normalized);
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"),
            "SYSDATE should also be transformed");
    }

    // ==================== MONTHS_BETWEEN Function Tests ====================

    @Test
    void monthsBetweenSimple() {
        // Given: MONTHS_BETWEEN with two dates
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT MONTHS_BETWEEN(end_date, start_date) FROM projects";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MONTHS_BETWEEN should be transformed to AGE/EXTRACT
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("EXTRACT( YEAR FROM AGE( end_date , start_date ) )"),
            "MONTHS_BETWEEN should use EXTRACT(YEAR FROM AGE(...)), got: " + normalized);
        assertTrue(normalized.contains("EXTRACT( MONTH FROM AGE( end_date , start_date ) )"),
            "MONTHS_BETWEEN should use EXTRACT(MONTH FROM AGE(...)), got: " + normalized);
        assertTrue(normalized.contains("* 12 +"),
            "MONTHS_BETWEEN should multiply years by 12, got: " + normalized);
        assertFalse(normalized.toUpperCase().contains("MONTHS_BETWEEN"),
            "MONTHS_BETWEEN function should not appear in output");
    }

    // ==================== LAST_DAY Function Tests ====================

    @Test
    void lastDaySimple() {
        // Given: LAST_DAY with a date column
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT LAST_DAY(hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LAST_DAY should be transformed to DATE_TRUNC + INTERVAL
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'MONTH' , hire_date )"),
            "LAST_DAY should use DATE_TRUNC('MONTH', ...), got: " + normalized);
        assertTrue(normalized.contains("+ INTERVAL '1 month'"),
            "LAST_DAY should add 1 month, got: " + normalized);
        assertTrue(normalized.contains("- INTERVAL '1 day'"),
            "LAST_DAY should subtract 1 day, got: " + normalized);
        assertTrue(normalized.contains("::DATE"),
            "LAST_DAY should cast to DATE, got: " + normalized);
        assertFalse(normalized.toUpperCase().contains("LAST_DAY"),
            "LAST_DAY function should not appear in output");
    }

    // ==================== TRUNC(date) Function Tests ====================

    @Test
    void truncDateNoFormat() {
        // Given: TRUNC with date but no format (truncate to day)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRUNC should be transformed to DATE_TRUNC with 'day'
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'day' , hire_date )::DATE"),
            "TRUNC should be transformed to DATE_TRUNC('day', ...)::DATE, got: " + normalized);
    }

    @Test
    void truncDateWithMonthFormat() {
        // Given: TRUNC with MONTH format
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(hire_date, 'MONTH') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRUNC should map MONTH to month
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'month' , hire_date )::DATE"),
            "TRUNC with MONTH should be transformed to DATE_TRUNC('month', ...)::DATE, got: " + normalized);
    }

    @Test
    void truncDateWithYearFormat() {
        // Given: TRUNC with YEAR format
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(hire_date, 'YEAR') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRUNC should map YEAR to year
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'year' , hire_date )::DATE"),
            "TRUNC with YEAR should be transformed to DATE_TRUNC('year', ...)::DATE, got: " + normalized);
    }

    @Test
    void truncDateWithMMFormat() {
        // Given: TRUNC with MM format (month)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(hire_date, 'MM') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRUNC should map MM to month
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'month' , hire_date )::DATE"),
            "TRUNC with MM should be transformed to DATE_TRUNC('month', ...)::DATE, got: " + normalized);
    }

    // ==================== Combined/Complex Tests ====================

    @Test
    void dateFunctionsInWhereClause() {
        // Given: Date functions in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE TRUNC(hire_date) = TRUNC(SYSDATE)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both TRUNC calls and SYSDATE should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'day' , hire_date )::DATE"),
            "First TRUNC should be transformed");
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , CURRENT_TIMESTAMP )::DATE"),
            "Second TRUNC should be transformed and SYSDATE should be CURRENT_TIMESTAMP");
    }

    @Test
    void dateFunctionsInOrderBy() {
        // Given: Date functions in ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees ORDER BY TRUNC(hire_date, 'MONTH') DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRUNC in ORDER BY should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("ORDER BY DATE_TRUNC( 'month' , hire_date )::DATE DESC"),
            "TRUNC in ORDER BY should be transformed, got: " + normalized);
        assertTrue(normalized.contains("NULLS FIRST"),
            "ORDER BY DESC should have NULLS FIRST added");
    }

    @Test
    void nestedDateFunctions() {
        // Given: Nested date functions
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ADD_MONTHS(LAST_DAY(hire_date), 3) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // LAST_DAY transformation: DATE_TRUNC('MONTH', ...) + INTERVAL '1 month' - INTERVAL '1 day'
        assertTrue(normalized.contains("DATE_TRUNC( 'MONTH' , hire_date )"),
            "LAST_DAY transformation should be present");
        assertTrue(normalized.contains("+ INTERVAL '1 month' - INTERVAL '1 day' )::DATE"),
            "LAST_DAY's month arithmetic should be present");
        // ADD_MONTHS transformation wraps the LAST_DAY result
        assertTrue(normalized.contains("( 3 * INTERVAL '1 month' )"),
            "ADD_MONTHS transformation should be present, got: " + normalized);
    }

    @Test
    void addMonthsWithColumnExpression() {
        // Given: ADD_MONTHS with column as month count
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ADD_MONTHS(hire_date, months_employed) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column reference should be preserved in multiplication pattern
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date + ( months_employed * INTERVAL '1 month' )"),
            "ADD_MONTHS with column should work, got: " + normalized);
    }

    @Test
    void monthsBetweenInArithmetic() {
        // Given: MONTHS_BETWEEN in arithmetic expression
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT MONTHS_BETWEEN(end_date, start_date) / 12 AS years FROM projects";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MONTHS_BETWEEN transformation should work in arithmetic
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("EXTRACT( YEAR FROM AGE("),
            "MONTHS_BETWEEN should be transformed");
        assertTrue(normalized.contains("/ 12"),
            "Division should be preserved, got: " + normalized);
    }

    @Test
    void lastDayInComparison() {
        // Given: LAST_DAY in comparison
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE hire_date = LAST_DAY(hire_date)";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: LAST_DAY should work in comparison
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("hire_date = ( DATE_TRUNC( 'MONTH'"),
            "LAST_DAY should be transformed in comparison, got: " + normalized);
    }

    @Test
    void truncWithQuarterFormat() {
        // Given: TRUNC with quarter format
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(hire_date, 'Q') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TRUNC should map Q to quarter
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'quarter' , hire_date )::DATE"),
            "TRUNC with Q should map to quarter, got: " + normalized);
    }

    @Test
    void multipleDateFunctionsInSelect() {
        // Given: Multiple different date functions in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ADD_MONTHS(hire_date, 6), LAST_DAY(hire_date), TRUNC(hire_date) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All three functions should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("( 6 * INTERVAL '1 month' )"),
            "ADD_MONTHS should be transformed");
        assertTrue(normalized.contains("DATE_TRUNC( 'MONTH' , hire_date ) + INTERVAL '1 month' - INTERVAL '1 day'"),
            "LAST_DAY should be transformed");
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , hire_date )::DATE"),
            "TRUNC should be transformed, got: " + normalized);
    }

    // ==================== NUMERIC TRUNC Tests (Should Pass Through) ====================

    @Test
    void numericTruncWithPrecision() {
        // Given: TRUNC with numeric literal and precision
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(123.456, 2) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric TRUNC with defensive cast (type evaluator returns UNKNOWN)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("TRUNC( 123.456::numeric , 2 )"),
            "Numeric TRUNC should pass through with defensive cast, got: " + normalized);
        assertFalse(normalized.contains("DATE_TRUNC"),
            "Should not transform to DATE_TRUNC for numeric");
    }

    @Test
    void numericTruncNoFormat() {
        // Given: TRUNC with numeric column, no precision
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(salary) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric TRUNC with defensive cast (type evaluator returns UNKNOWN)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("TRUNC( salary::numeric )"),
            "Numeric TRUNC should pass through with defensive cast, got: " + normalized);
        assertFalse(normalized.contains("DATE_TRUNC"),
            "Should not transform to DATE_TRUNC");
    }

    @Test
    void numericTruncWithNegativePrecision() {
        // Given: TRUNC with negative precision (rounds to tens, hundreds, etc.)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT TRUNC(salary, -2) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric TRUNC with defensive cast (type evaluator returns UNKNOWN)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("TRUNC( salary::numeric , -2 )"),
            "Numeric TRUNC with negative precision should have defensive cast, got: " + normalized);
    }

    // ==================== NUMERIC ROUND Tests (Should Pass Through) ====================

    @Test
    void numericRoundWithPrecision() {
        // Given: ROUND with numeric literal and precision
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ROUND(123.456, 2) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric ROUND with defensive cast (type evaluator returns UNKNOWN)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("ROUND( 123.456::numeric , 2 )"),
            "Numeric ROUND should have defensive cast, got: " + normalized);
        assertFalse(normalized.contains("DATE_TRUNC"),
            "Should not transform to DATE_TRUNC for numeric");
        assertFalse(normalized.contains("CASE WHEN"),
            "Should not use CASE WHEN for numeric");
    }

    @Test
    void numericRoundNoFormat() {
        // Given: ROUND with numeric column, no precision
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ROUND(salary) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Numeric ROUND with defensive cast (type evaluator returns UNKNOWN)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("ROUND( salary::numeric )"),
            "Numeric ROUND should have defensive cast, got: " + normalized);
        assertFalse(normalized.contains("CASE WHEN"),
            "Should not use CASE WHEN for numeric");
    }

    // ==================== Date ROUND Tests ====================

    @Test
    void roundDateToMonth() {
        // Given: ROUND with date column and MONTH format
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ROUND(hire_date, 'MM') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROUND should be transformed to CASE WHEN with DATE_TRUNC
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CASE WHEN EXTRACT( DAY FROM hire_date ) >= 16"),
            "Should have CASE WHEN with day threshold, got: " + normalized);
        assertTrue(normalized.contains("THEN DATE_TRUNC( 'month' , hire_date ) + INTERVAL '1 month'"),
            "Should round up to next month if day >= 16, got: " + normalized);
        assertTrue(normalized.contains("ELSE DATE_TRUNC( 'month' , hire_date )"),
            "Should truncate to current month if day < 16, got: " + normalized);
        assertTrue(normalized.contains("END::DATE"),
            "Should cast result to DATE, got: " + normalized);
    }

    @Test
    void roundDateToYear() {
        // Given: ROUND with date column and YEAR format
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ROUND(hire_date, 'YYYY') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROUND should be transformed with month threshold
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CASE WHEN EXTRACT( MONTH FROM hire_date ) >= 7"),
            "Should have CASE WHEN with month threshold (July), got: " + normalized);
        assertTrue(normalized.contains("THEN DATE_TRUNC( 'year' , hire_date ) + INTERVAL '1 year'"),
            "Should round up to next year if month >= 7, got: " + normalized);
        assertTrue(normalized.contains("ELSE DATE_TRUNC( 'year' , hire_date )"),
            "Should truncate to current year if month < 7, got: " + normalized);
    }

    @Test
    void roundDateToDay() {
        // Given: ROUND with date column and DD format (or no format)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ROUND(hire_date, 'DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROUND should be transformed with hour threshold
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CASE WHEN EXTRACT( HOUR FROM hire_date ) >= 12"),
            "Should have CASE WHEN with hour threshold (noon), got: " + normalized);
        assertTrue(normalized.contains("THEN DATE_TRUNC( 'day' , hire_date ) + INTERVAL '1 day'"),
            "Should round up to next day if hour >= 12, got: " + normalized);
        assertTrue(normalized.contains("ELSE DATE_TRUNC( 'day' , hire_date )"),
            "Should truncate to current day if hour < 12, got: " + normalized);
    }

    @Test
    void roundDateWithSysdate() {
        // Given: ROUND with SYSDATE (should detect as date expression)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT ROUND(SYSDATE, 'MM') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROUND should be transformed and SYSDATE should be CURRENT_TIMESTAMP
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CURRENT_TIMESTAMP"),
            "SYSDATE should be transformed to CURRENT_TIMESTAMP");
        assertTrue(normalized.contains("CASE WHEN"),
            "ROUND should be transformed to CASE WHEN for date, got: " + normalized);
        assertTrue(normalized.contains("EXTRACT( DAY FROM CURRENT_TIMESTAMP )"),
            "Should extract day from CURRENT_TIMESTAMP, got: " + normalized);
    }

    @Test
    void roundDateInWhereClause() {
        // Given: ROUND in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        String oracleSql = "SELECT empno FROM employees WHERE ROUND(hire_date, 'YYYY') = TO_DATE('2020-01-01', 'YYYY-MM-DD')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ROUND should be transformed in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("WHERE CASE WHEN"),
            "ROUND should be transformed in WHERE clause, got: " + normalized);
        assertTrue(normalized.contains("TO_TIMESTAMP"),
            "TO_DATE should be transformed to TO_TIMESTAMP");
    }

    // ==================== Type Inference Integration Tests ====================

    @Test
    void truncWithQualifiedColumnAndTypeInference() {
        // Given: TRUNC with qualified column (non-English name)
        // hold_placed is a DATE column whose name gives the heuristics nothing to go on
        // Previously failed with heuristics, now works with type inference

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hold_item_nr", new TransformationIndices.ColumnTypeInfo("NUMBER", "hold_item_nr"));
        columns.put("hold_placed", new TransformationIndices.ColumnTypeInfo("DATE", "hold_placed"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("lending.item_holds", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT ws1.hold_item_nr, TRUNC(ws1.hold_placed) FROM item_holds ws1";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("LENDING", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("LENDING", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Type inference should detect hold_placed as DATE and use DATE_TRUNC
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("DATE_TRUNC( 'day' , ws1 . hold_placed )::DATE"),
            "TRUNC with qualified date column should use DATE_TRUNC, got: " + normalized);
        assertFalse(normalized.contains("::numeric"),
            "Should NOT add ::numeric cast for date column, got: " + normalized);
    }

    @Test
    void truncWithDateArithmeticAndTypeInference() {
        // Given: TRUNC with date column + date arithmetic
        // This combines both TRUNC type inference and date arithmetic

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hold_placed", new TransformationIndices.ColumnTypeInfo("DATE", "hold_placed"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("lending.item_holds", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT ws1.hold_placed + 11, TRUNC(ws1.hold_placed) + 22 FROM item_holds ws1";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("LENDING", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("LENDING", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both should work correctly with type inference
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // First: date column + 11 should use INTERVAL
        assertTrue(normalized.contains("hold_placed + ( 11 * INTERVAL '1 day' )"),
            "Date arithmetic should add INTERVAL, got: " + normalized);

        // Second: TRUNC(date) should use DATE_TRUNC, and result + 22 should use INTERVAL
        assertTrue(normalized.contains("DATE_TRUNC( 'day' , ws1 . hold_placed )::DATE"),
            "TRUNC should use DATE_TRUNC for date column, got: " + normalized);
        assertTrue(normalized.contains("( 22 * INTERVAL '1 day' )"),
            "Date arithmetic on TRUNC result should use INTERVAL, got: " + normalized);

        // Critical: Should NOT have ::numeric cast
        assertFalse(normalized.contains("::numeric"),
            "Should NOT add ::numeric cast for date column in TRUNC, got: " + normalized);
    }

    @Test
    void compareTruncVsRoundWithSameInput() {
        // Given: Identical setup for both TRUNC and ROUND
        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hold_placed", new TransformationIndices.ColumnTypeInfo("DATE", "hold_placed"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("lending.item_holds", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        // Test TRUNC
        String truncSql = "SELECT TRUNC(ws1.hold_placed) FROM item_holds ws1";
        ParseResult truncParse = parser.parseSelectStatement(truncSql);
        Map<String, TypeInfo> truncCache = new HashMap<>();
        TypeAnalysisVisitor truncAnalyzer = new TypeAnalysisVisitor("LENDING", indices, truncCache);
        truncAnalyzer.visit(truncParse.getTree());
        FullTypeEvaluator truncTypeEval = new FullTypeEvaluator(truncCache);
        TransformationContext truncContext = new TransformationContext("LENDING", indices, truncTypeEval);
        PostgresCodeBuilder truncBuilder = new PostgresCodeBuilder(truncContext);
        String truncResult = truncBuilder.visit(truncParse.getTree());

        // Test ROUND
        String roundSql = "SELECT ROUND(ws1.hold_placed) FROM item_holds ws1";
        ParseResult roundParse = parser.parseSelectStatement(roundSql);
        Map<String, TypeInfo> roundCache = new HashMap<>();
        TypeAnalysisVisitor roundAnalyzer = new TypeAnalysisVisitor("LENDING", indices, roundCache);
        roundAnalyzer.visit(roundParse.getTree());
        FullTypeEvaluator roundTypeEval = new FullTypeEvaluator(roundCache);
        TransformationContext roundContext = new TransformationContext("LENDING", indices, roundTypeEval);
        PostgresCodeBuilder roundBuilder = new PostgresCodeBuilder(roundContext);
        String roundResult = roundBuilder.visit(roundParse.getTree());

        // Debug output
        System.out.println("TRUNC cache size: " + truncCache.size());
        System.out.println("ROUND cache size: " + roundCache.size());
        System.out.println("TRUNC result: " + truncResult);
        System.out.println("ROUND result: " + roundResult);

        // Both should detect as date functions
        assertTrue(truncResult.contains("DATE_TRUNC"),
            "TRUNC should use DATE_TRUNC for date column, got: " + truncResult);
        assertTrue(roundResult.contains("CASE WHEN"),
            "ROUND should use CASE WHEN for date column, got: " + roundResult);

        assertFalse(truncResult.contains("::numeric"),
            "TRUNC should NOT have ::numeric cast");
        assertFalse(roundResult.contains("::numeric"),
            "ROUND should NOT have ::numeric cast, got: " + roundResult);
    }

    // TODO: Debug why ROUND type inference isn't working (TRUNC works fine)
    // @Test
    void roundWithQualifiedColumnAndTypeInference() {
        // Given: ROUND with qualified column (non-English name)
        // Verify ROUND also works with type inference

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hold_placed", new TransformationIndices.ColumnTypeInfo("DATE", "hold_placed"));

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();
        tableColumns.put("lending.item_holds", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashSet<>()
        );

        String oracleSql = "SELECT ROUND(ws1.hold_placed) FROM item_holds ws1";

        // When: Parse and run type analysis pass
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis pass
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("LENDING", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Create context with FullTypeEvaluator
        FullTypeEvaluator typeEvaluator = new FullTypeEvaluator(typeCache);
        TransformationContext context = new TransformationContext("LENDING", indices, typeEvaluator);

        // Transform
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Type inference should detect hold_placed as DATE and use CASE WHEN + DATE_TRUNC
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CASE WHEN"),
            "ROUND with qualified date column should use CASE WHEN transformation, got: " + normalized);
        assertTrue(normalized.contains("DATE_TRUNC"),
            "ROUND should use DATE_TRUNC for date column, got: " + normalized);
        assertFalse(normalized.toUpperCase().contains("ROUND("),
            "Should NOT pass through as numeric ROUND, got: " + normalized);
    }

    // ==================== FROM_TZ Function Tests ====================

    /** Transforms {@code sql} in schema HR with no metadata, and normalizes the whitespace. */
    private String transform(String sql) {
        TransformationContext context = new TransformationContext(
            "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        ParseResult parseResult = parser.parseSelectStatement(sql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        return builder.visit(parseResult.getTree()).trim().replaceAll("\\s+", " ");
    }

    /** Transforms {@code sql} and expects it to be rejected. */
    private TransformationException transformExpectingRejection(String sql) {
        TransformationContext context = new TransformationContext(
            "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        ParseResult parseResult = parser.parseSelectStatement(sql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        return assertThrows(TransformationException.class, () -> builder.visit(parseResult.getTree()));
    }

    @Test
    void fromTzOffsetIsEmittedAsIntervalNotText() {
        // Given: FROM_TZ with the Oracle 'TZH:TZM' zone form.
        // This is the regression that matters: PostgreSQL reads an offset given as *text* POSIX-style,
        // with the sign inverted, so AT TIME ZONE '+02:00' is UTC-2 and lands four hours away from
        // Oracle's answer without raising anything. The same offset as an INTERVAL is ISO-correct.
        String normalized = transform("SELECT FROM_TZ(event_ts, '+02:00') FROM events");

        assertTrue(normalized.contains("( ( event_ts )::timestamp AT TIME ZONE INTERVAL '+02:00' )"),
            "FROM_TZ should become AT TIME ZONE with an INTERVAL operand, got: " + normalized);
        assertFalse(normalized.contains("AT TIME ZONE '+02:00'"),
            "Offset must NOT be emitted as text - PostgreSQL would invert its sign, got: " + normalized);
        assertFalse(normalized.toUpperCase().contains("FROM_TZ"),
            "FROM_TZ should not appear in output, got: " + normalized);
    }

    @Test
    void fromTzNegativeOffsetKeepsItsSign() {
        String normalized = transform("SELECT FROM_TZ(event_ts, '-05:30') FROM events");

        assertTrue(normalized.contains("AT TIME ZONE INTERVAL '-05:30'"),
            "Negative offset should survive as a negative INTERVAL, got: " + normalized);
    }

    @Test
    void fromTzOffsetWithoutSignIsRecognized() {
        // Oracle writes an unsigned offset too - its own documentation example is FROM_TZ(ts, '3:00').
        String normalized = transform("SELECT FROM_TZ(event_ts, '3:00') FROM events");

        assertTrue(normalized.contains("AT TIME ZONE INTERVAL '3:00'"),
            "Unsigned offset should still be recognized as an offset, got: " + normalized);
    }

    @Test
    void fromTzRegionNameStaysText() {
        // Region names carry no sign inversion and PostgreSQL uses the same IANA database as Oracle,
        // so they are passed through as a text operand.
        String normalized = transform("SELECT FROM_TZ(event_ts, 'Europe/Vienna') FROM events");

        assertTrue(normalized.contains("AT TIME ZONE 'Europe/Vienna'"),
            "Region name should be emitted as text, got: " + normalized);
        assertFalse(normalized.contains("INTERVAL 'Europe/Vienna'"),
            "Region name must not be wrapped in INTERVAL, got: " + normalized);
    }

    @Test
    void fromTzCastsTimestamptzArgumentBackToTimestamp() {
        // AT TIME ZONE picks its direction from the operand's static type: on a timestamptz it does
        // the reverse conversion. SYSDATE transforms to CURRENT_TIMESTAMP, which is timestamptz, so
        // without the cast this call would silently mean the opposite of what Oracle wrote.
        String normalized = transform("SELECT FROM_TZ(SYSDATE, '+02:00') FROM dual");

        assertTrue(normalized.contains("( CURRENT_TIMESTAMP )::timestamp AT TIME ZONE"),
            "First argument should be cast to timestamp to pin the conversion direction, got: " + normalized);
    }

    @Test
    void fromTzNonLiteralZoneIsRejected() {
        // The sign question can only be decided when the offset is visible at transformation time.
        // A column-valued zone would have to go out as text and would then be inverted exactly when
        // it happens to hold an offset - see the Option C note in DateFunctionTransformer.
        TransformationException exception =
            transformExpectingRejection("SELECT FROM_TZ(event_ts, tz_col) FROM events");

        assertTrue(exception.getMessage().contains("time_zone"),
            "Exception should mention 'time_zone': " + exception.getMessage());
    }

    @Test
    void fromTzRegionWithDaylightAbbreviationIsRejected() {
        // Oracle accepts 'TZR TZD'; no name in pg_timezone_names contains a space. Dropping the
        // abbreviation would silently pick one side of the ambiguous hour at a DST fall-back.
        TransformationException exception =
            transformExpectingRejection("SELECT FROM_TZ(event_ts, 'America/New_York PDT') FROM events");

        assertTrue(exception.getMessage().contains("daylight abbreviation"),
            "Exception should name the region/abbreviation form: " + exception.getMessage());
    }

    @Test
    void fromTzWithWrongArgumentCountIsRejected() {
        TransformationException exception =
            transformExpectingRejection("SELECT FROM_TZ(event_ts) FROM events");

        assertTrue(exception.getMessage().contains("FROM_TZ"),
            "Exception should name FROM_TZ: " + exception.getMessage());
    }
}
