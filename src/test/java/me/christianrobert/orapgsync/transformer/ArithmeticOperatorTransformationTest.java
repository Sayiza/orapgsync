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
 * Tests for arithmetic operator transformation.
 *
 * <p>Key insight: Oracle and PostgreSQL have nearly identical arithmetic operator syntax.
 * The main transformation needed is schema qualification of table names.
 *
 * <p>This test verifies that queries using arithmetic operators (+, -, *, /, MOD, **)
 * are transformed correctly with proper operator precedence and parentheses handling.
 *
 * <p>Tested operators:
 * <ul>
 *   <li>Addition (+) - Same in both databases</li>
 *   <li>Subtraction (-) - Same in both databases</li>
 *   <li>Multiplication (*) - Same in both databases</li>
 *   <li>Division (/) - Same in both databases</li>
 *   <li>MOD (modulo) - Oracle MOD() function, PostgreSQL MOD() function (same)</li>
 *   <li>Power (**) - Oracle **, PostgreSQL ^ (transformation needed)</li>
 *   <li>String concatenation (||) - Same in both databases</li>
 * </ul>
 */
class ArithmeticOperatorTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== ADDITION TESTS ==========

    @Test
    void additionInSelectList() {
        // Given: Simple addition in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT salary + bonus FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Addition preserved with schema-qualified table
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary + bonus"), "Addition operator should be preserved");
        assertTrue(normalized.contains("FROM hr.employees"), "Table should be schema-qualified");
    }

    @Test
    void additionWithLiterals() {
        // Given: Addition with numeric literals
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno, salary + 1000 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Addition with literal preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary + 1000"), "Addition with literal should be preserved");
    }

    // ========== SUBTRACTION TESTS ==========

    @Test
    void subtractionInSelectList() {
        // Given: Simple subtraction in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT salary - tax FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Subtraction preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary - tax"), "Subtraction operator should be preserved");
    }

    // ========== MULTIPLICATION TESTS ==========

    @Test
    void multiplicationInSelectList() {
        // Given: Simple multiplication (annual salary calculation)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT salary * 12 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Multiplication preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary * 12"), "Multiplication operator should be preserved");
        assertTrue(normalized.contains("FROM hr.employees"), "Table should be schema-qualified");
    }

    @Test
    void multiplicationOfTwoColumns() {
        // Given: Multiplication of two columns
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT quantity * price FROM orders";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Multiplication preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("quantity * price"), "Multiplication of columns should be preserved");
    }

    // ========== DIVISION TESTS ==========

    @Test
    void divisionInSelectList() {
        // Given: Simple division
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT total / count AS average FROM stats";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Division preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("total / count"), "Division operator should be preserved");
    }

    // ========== MODULO TESTS ==========

    @Test
    void moduloOperator() {
        // Given: MOD operator
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno MOD 2 FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: MOD preserved as MOD function
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        // MOD operator produces: MOD( expr , expr )
        assertTrue(normalized.contains("MOD(") && normalized.contains("empno") && normalized.contains("2"),
                "MOD should be transformed to MOD function");
    }

    // ========== POWER OPERATOR TESTS ==========

    @Test
    void powerOperator() {
        // Given: Power operator (Oracle **)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT base ** exponent AS power_result FROM calculations";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ** transformed to ^ (PostgreSQL power operator)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("base ^ exponent"), "Oracle ** should be transformed to PostgreSQL ^");
    }

    @Test
    void powerWithLiteral() {
        // Given: Power with literal (square calculation)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT radius ** 2 AS area_approx FROM circles";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: ** transformed to ^
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("radius ^ 2"), "Oracle ** should be transformed to PostgreSQL ^");
    }

    // ========== STRING CONCATENATION TESTS ==========

    @Test
    void stringConcatenation() {
        // Given: String concatenation with || operator
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT first_name || ' ' || last_name FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: || transformed to CONCAT() for NULL-safe behavior
        // Oracle: NULL treated as empty string
        // PostgreSQL ||: NULL propagates (WRONG!)
        // PostgreSQL CONCAT(): NULL treated as empty string (CORRECT!)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CONCAT("), "|| should be transformed to CONCAT()");
        assertTrue(normalized.contains("first_name"), "first_name should be in CONCAT");
        assertTrue(normalized.contains("last_name"), "last_name should be in CONCAT");
    }

    @Test
    void stringConcatenationWithNullLiteral() {
        // Given: String concatenation with explicit NULL
        // This is the CRITICAL difference between Oracle and PostgreSQL
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT 'Hello' || NULL || 'World' FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CONCAT() ensures NULL is treated as empty string (Oracle behavior)
        // Oracle result: 'HelloWorld'
        // PostgreSQL || result: NULL (WRONG!)
        // PostgreSQL CONCAT() result: 'HelloWorld' (CORRECT!)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CONCAT("), "|| should be transformed to CONCAT()");
        assertTrue(normalized.contains("'Hello'"), "First string literal should be present");
        assertTrue(normalized.contains("NULL"), "NULL should be present");
        assertTrue(normalized.contains("'World'"), "Second string literal should be present");
    }

    @Test
    void stringConcatenationChained() {
        // Given: Multiple chained concatenations
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT a || b || c || d FROM test_table";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Nested CONCAT calls (left-recursive grammar)
        // a || b || c || d becomes CONCAT(CONCAT(CONCAT(a, b), c), d)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");

        // Count CONCAT occurrences (should be 3 for 4 operands)
        int concatCount = normalized.split("CONCAT\\(", -1).length - 1;
        assertEquals(3, concatCount, "Chained concatenation should produce nested CONCAT calls");
    }

    @Test
    void stringConcatenationInWhereClause() {
        // Given: String concatenation in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE first_name || last_name = 'JohnSmith'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: CONCAT in WHERE clause
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE CONCAT(") || normalized.contains("WHERE CONCAT ("),
                "Concatenation in WHERE should use CONCAT()");
    }

    @Test
    void stringConcatenationMixedWithArithmetic() {
        // Given: Mix of string concatenation and arithmetic
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT name || ' earns ' || (salary * 12) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both CONCAT and arithmetic operators present
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("CONCAT("), "String concatenation should use CONCAT()");
        assertTrue(normalized.contains("salary * 12"), "Arithmetic should be preserved");
    }

    // ========== COMPLEX EXPRESSIONS ==========

    @Test
    void complexArithmeticExpression() {
        // Given: Complex expression with multiple operators
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT (salary * 12) + bonus - tax FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex expression preserved with proper precedence
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary * 12"), "Multiplication should be preserved");
        assertTrue(normalized.contains("+ bonus"), "Addition should be preserved");
        assertTrue(normalized.contains("- tax"), "Subtraction should be preserved");
        assertTrue(normalized.contains("FROM hr.employees"), "Table should be schema-qualified");
    }

    @Test
    void arithmeticWithParentheses() {
        // Given: Arithmetic with explicit parentheses for precedence
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT (salary + bonus) * 12 AS annual_total FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Parentheses preserved for correct precedence
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("( salary + bonus )") || normalized.contains("(salary + bonus)"),
                "Parentheses should be preserved for precedence");
        assertTrue(normalized.contains("* 12"), "Multiplication should follow parentheses");
    }

    @Test
    void nestedArithmeticExpressions() {
        // Given: Nested arithmetic expressions
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT ((base_salary + bonus) * 12) - (tax * 12) AS net_annual FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All operators and parentheses preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("base_salary + bonus"), "Inner addition preserved");
        assertTrue(normalized.contains("* 12"), "Multiplication preserved");
        assertTrue(normalized.contains("tax * 12"), "Second multiplication preserved");
        assertTrue(normalized.contains("-"), "Subtraction preserved");
    }

    // ========== ARITHMETIC IN WHERE CLAUSE ==========

    @Test
    void arithmeticInWhereClause() {
        // Given: Arithmetic expression in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE salary * 12 > 100000";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Arithmetic in WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE salary * 12 > 100000"),
                "Arithmetic expression in WHERE should be preserved");
    }

    @Test
    void arithmeticInWhereWithMultipleOperators() {
        // Given: Complex arithmetic in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE (salary + bonus) * 12 > 150000 AND tax < 50000";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex arithmetic in WHERE preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("( salary + bonus ) * 12") || normalized.contains("(salary + bonus) * 12"),
                "Parenthesized addition should be preserved");
        assertTrue(normalized.contains("> 150000"), "Comparison should be preserved");
        assertTrue(normalized.contains("AND"), "Logical operator should be preserved");
    }

    // ========== ARITHMETIC IN ORDER BY ==========

    @Test
    void arithmeticInOrderBy() {
        // Given: Arithmetic expression in ORDER BY
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees ORDER BY salary * 12 DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Arithmetic in ORDER BY preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY salary * 12 DESC"),
                "Arithmetic expression in ORDER BY should be preserved");
        // Note: DESC gets NULLS FIRST added automatically
        assertTrue(normalized.contains("NULLS FIRST"), "DESC should add NULLS FIRST");
    }

    // ========== MIXED OPERATORS ==========

    @Test
    void allOperatorsTogether() {
        // Given: Expression using all arithmetic operators
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT (a + b) * (c - d) / e MOD f ** 2 FROM calculations";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: All operators transformed correctly
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("+"), "Addition preserved");
        assertTrue(normalized.contains("-"), "Subtraction preserved");
        assertTrue(normalized.contains("*"), "Multiplication preserved");
        assertTrue(normalized.contains("/"), "Division preserved");
        assertTrue(normalized.contains("MOD"), "MOD preserved");
        assertTrue(normalized.contains("^"), "Power operator ** transformed to ^");
    }

    // ========== WITHOUT CONTEXT ==========

    @Test
    void arithmeticWithoutContext() {
        // Given: Arithmetic without transformation context
        String oracleSql = "SELECT salary * 12 FROM employees";

        // When: Parse and transform without context
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder();
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Arithmetic works without context (no schema qualification)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("salary * 12"), "Arithmetic should work without context");
        assertTrue(normalized.contains("FROM employees"), "Table should not be schema-qualified without context");
    }
}
