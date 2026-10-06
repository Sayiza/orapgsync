package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for unary operator transformation (+, -).
 *
 * <p>Oracle and PostgreSQL have identical unary operator syntax - pass-through transformation.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>Oracle: -5 → PostgreSQL: -5</li>
 *   <li>Oracle: +10 → PostgreSQL: +10</li>
 *   <li>Oracle: -column_name → PostgreSQL: -column_name</li>
 * </ul>
 */
class UnaryOperatorTransformationTest {

  private AntlrParser parser;
  private PostgresCodeBuilder builder;

  @BeforeEach
  void setUp() {
    parser = new AntlrParser();
    builder = new PostgresCodeBuilder();
  }

  private String transform(String oracleSql) {
    ParseResult parseResult = parser.parseSelectStatement(oracleSql);
    assertFalse(parseResult.hasErrors(), "Parse should succeed");
    return builder.visit(parseResult.getTree());
  }

  // ========== Unary Minus (-) ==========

  @Test
  void unaryMinusWithLiteral() {
    String oracleSql = "SELECT -5 FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -5"),
        "Should preserve unary minus with literal");
  }

  @Test
  void unaryMinusWithColumn() {
    String oracleSql = "SELECT -salary FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -salary"),
        "Should preserve unary minus with column reference");
  }

  @Test
  void unaryMinusWithDecimal() {
    String oracleSql = "SELECT -3.14 FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -3.14"),
        "Should preserve unary minus with decimal literal");
  }

  @Test
  void unaryMinusInWhereClause() {
    String oracleSql = "SELECT empno FROM employees WHERE salary > -1000";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("WHERE salary > -1000"),
        "Should preserve unary minus in WHERE clause");
  }

  @Test
  void unaryMinusInOrderBy() {
    String oracleSql = "SELECT empno FROM employees ORDER BY -salary DESC";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("ORDER BY -salary DESC"),
        "Should preserve unary minus in ORDER BY clause");
  }

  @Test
  void unaryMinusWithExpression() {
    String oracleSql = "SELECT -(salary * 12) FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    // The output may have different spacing around parentheses
    assertTrue(normalized.contains("SELECT -") && normalized.contains("salary * 12"),
        "Should preserve unary minus with expression, actual: " + normalized);
  }

  // ========== Unary Plus (+) ==========

  @Test
  void unaryPlusWithLiteral() {
    String oracleSql = "SELECT +5 FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT +5"),
        "Should preserve unary plus with literal");
  }

  @Test
  void unaryPlusWithColumn() {
    String oracleSql = "SELECT +salary FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT +salary"),
        "Should preserve unary plus with column reference");
  }

  @Test
  void unaryPlusInWhereClause() {
    String oracleSql = "SELECT empno FROM employees WHERE salary = +50000";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("WHERE salary = +50000"),
        "Should preserve unary plus in WHERE clause");
  }

  // ========== Mixed Scenarios ==========

  @Test
  void multipleUnaryOperators() {
    String oracleSql = "SELECT -salary, +bonus FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -salary , +bonus"),
        "Should preserve multiple unary operators");
  }

  @Test
  void doubleNegative() {
    // Note: -- is a SQL comment, so --5 would be interpreted as comment
    // Using -(-5) instead for true double negative
    String oracleSql = "SELECT -(-5) FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    // The spacing may vary, just check that we have minus and -5
    assertTrue(normalized.contains("SELECT -") && normalized.contains("-5"),
        "Should preserve double negative, actual: " + normalized);
  }

  @Test
  void unaryMinusWithQualifiedColumn() {
    String oracleSql = "SELECT -e.salary FROM employees e";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -e . salary"),
        "Should preserve unary minus with qualified column");
  }

  @Test
  void unaryOperatorWithFunction() {
    String oracleSql = "SELECT -COUNT(*) FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -COUNT( * )"),
        "Should preserve unary minus with function");
  }

  @Test
  void unaryOperatorWithArithmetic() {
    String oracleSql = "SELECT -salary + 1000 FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -salary + 1000"),
        "Should preserve unary operator with arithmetic");
  }

  @Test
  void unaryOperatorInCaseExpression() {
    String oracleSql = "SELECT CASE WHEN dept_id = 10 THEN -100 ELSE +100 END FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("THEN -100"),
        "Should preserve unary minus in CASE THEN");
    assertTrue(normalized.contains("ELSE +100"),
        "Should preserve unary plus in CASE ELSE");
  }

  @Test
  void unaryMinusWithSubquery() {
    String oracleSql = "SELECT empno FROM employees WHERE salary > -(SELECT AVG(salary) FROM employees)";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    // The spacing around the negative sign and parenthesis may vary
    assertTrue(normalized.contains("WHERE salary > -") && normalized.contains("SELECT AVG"),
        "Should preserve unary minus with subquery, actual: " + normalized);
  }

  @Test
  void unaryOperatorWithFromDual() {
    String oracleSql = "SELECT -1, +2 FROM DUAL";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -1 , +2"),
        "Should preserve unary operators with FROM DUAL");
    // FROM DUAL should be removed
    assertFalse(normalized.toUpperCase().contains("FROM DUAL"),
        "FROM DUAL should be removed");
  }

  @Test
  void unaryOperatorWithAlias() {
    String oracleSql = "SELECT -salary AS negative_salary FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -salary AS negative_salary"),
        "Should preserve unary operator with column alias");
  }

  @Test
  void unaryOperatorWithNullValue() {
    String oracleSql = "SELECT -NULL FROM employees";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -NULL"),
        "Should preserve unary minus with NULL");
  }

  @Test
  void unaryOperatorInGroupBy() {
    String oracleSql = "SELECT -dept_id, COUNT(*) FROM employees GROUP BY -dept_id";
    String result = transform(oracleSql);

    String normalized = result.trim().replaceAll("\\s+", " ");
    assertTrue(normalized.contains("SELECT -dept_id"),
        "Should preserve unary minus in SELECT");
    assertTrue(normalized.contains("GROUP BY -dept_id"),
        "Should preserve unary minus in GROUP BY");
  }
}
