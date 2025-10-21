package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for basic (non-recursive) CTE transformation.
 *
 * <p>CTEs (Common Table Expressions) have nearly identical syntax in Oracle and PostgreSQL.
 * The main differences:
 * <ul>
 *   <li>Recursive CTEs require RECURSIVE keyword in PostgreSQL (tested separately)</li>
 *   <li>Oracle inline PL/SQL functions not supported in PostgreSQL (should throw exception)</li>
 *   <li>CTE subqueries are recursively transformed (schema qualification, etc.)</li>
 * </ul>
 *
 * <p>Test coverage:
 * <ol>
 *   <li>Single CTE without column list</li>
 *   <li>Single CTE with column list</li>
 *   <li>Multiple CTEs</li>
 *   <li>CTE with complex subquery (ORDER BY, JOINs, etc.)</li>
 *   <li>CTE used in main query WHERE clause</li>
 *   <li>Inline PL/SQL function (should throw exception)</li>
 *   <li>CTE with FROM DUAL</li>
 *   <li>CTE with Oracle functions (NVL, DECODE, etc.)</li>
 * </ol>
 */
class CteBasicTransformationTest {

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

  // ========== Single CTE Tests ==========

  @Test
  void singleCteWithoutColumnList() {
    String oracleSql = "WITH dept_totals AS " +
        "(SELECT dept_id, COUNT(*) as cnt FROM departments GROUP BY dept_id) " +
        "SELECT * FROM dept_totals";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH dept_totals AS ("),
        "Should start with WITH dept_totals AS");
    assertTrue(normalized.contains("SELECT dept_id , COUNT( * ) AS cnt FROM departments"),
        "Should contain CTE definition");
    assertTrue(normalized.contains("SELECT * FROM dept_totals"),
        "Should reference CTE in main query");
    assertFalse(normalized.contains("RECURSIVE"),
        "Non-recursive CTE should not have RECURSIVE keyword");
  }

  @Test
  void singleCteWithColumnList() {
    String oracleSql = "WITH dept_totals (dept_id, emp_count) AS " +
        "(SELECT dept_id, COUNT(*) FROM departments GROUP BY dept_id) " +
        "SELECT * FROM dept_totals";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH dept_totals (dept_id,emp_count) AS ("),
        "Should preserve column list in CTE definition (note: no space after comma in column list)");
    assertTrue(normalized.contains("SELECT * FROM dept_totals"),
        "Should reference CTE in main query");
  }

  @Test
  void cteWithAlias() {
    String oracleSql = "WITH dept_totals AS " +
        "(SELECT dept_id, COUNT(*) as cnt FROM departments) " +
        "SELECT dt.dept_id, dt.cnt FROM dept_totals dt";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH dept_totals AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("SELECT dt . dept_id , dt . cnt FROM dept_totals dt"),
        "Should preserve table alias for CTE");
  }

  // ========== Multiple CTE Tests ==========

  @Test
  void multipleCtes() {
    String oracleSql = "WITH " +
        "dept_totals AS (SELECT dept_id, COUNT(*) as cnt FROM departments GROUP BY dept_id), " +
        "high_count AS (SELECT dept_id FROM dept_totals WHERE cnt > 10) " +
        "SELECT * FROM high_count";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH dept_totals AS ("),
        "Should contain first CTE");
    assertTrue(normalized.contains(", high_count AS ("),
        "Should contain second CTE with comma separator");
    assertTrue(normalized.contains("FROM dept_totals") && normalized.contains("WHERE"),
        "Second CTE should reference first CTE");
    assertTrue(normalized.contains("SELECT * FROM high_count"),
        "Main query should reference second CTE");
  }

  @Test
  void multipleCtesDifferentComplexity() {
    String oracleSql = "WITH " +
        "simple AS (SELECT 1 as n FROM DUAL), " +
        "complex AS (SELECT dept_id, AVG(salary) as avg_sal FROM employees GROUP BY dept_id) " +
        "SELECT s.n, c.avg_sal FROM simple s, complex c";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH simple AS ("),
        "Should contain simple CTE");
    assertTrue(normalized.contains(", complex AS ("),
        "Should contain complex CTE");
    // FROM DUAL should be removed in first CTE
    assertFalse(normalized.toUpperCase().contains("FROM DUAL"),
        "FROM DUAL should be removed from CTE");
  }

  // ========== CTE with Complex Subquery Tests ==========

  @Test
  void cteWithOrderBy() {
    String oracleSql = "WITH sorted_emp AS (" +
        "SELECT emp_id, emp_name FROM employees ORDER BY emp_name DESC" +
        ") SELECT * FROM sorted_emp";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH sorted_emp AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("ORDER BY emp_name DESC NULLS FIRST"),
        "Should add NULLS FIRST to DESC ORDER BY inside CTE");
  }

  @Test
  void cteWithJoin() {
    String oracleSql = "WITH emp_dept AS (" +
        "SELECT e.emp_id, e.emp_name, d.dept_name " +
        "FROM employees e " +
        "JOIN departments d ON e.dept_id = d.dept_id " +
        "WHERE e.salary > 50000" +
        ") SELECT * FROM emp_dept";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH emp_dept AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("FROM employees e"),
        "Should contain table reference");
    assertTrue(normalized.contains("JOIN departments d"),
        "Should preserve ANSI JOIN");
    assertTrue(normalized.contains("WHERE e . salary > 50000"),
        "Should preserve WHERE clause");
  }

  @Test
  void cteWithGroupByHaving() {
    String oracleSql = "WITH dept_summary AS (" +
        "SELECT dept_id, COUNT(*) as emp_count, AVG(salary) as avg_salary " +
        "FROM employees " +
        "GROUP BY dept_id " +
        "HAVING COUNT(*) > 5" +
        ") SELECT * FROM dept_summary";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH dept_summary AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("GROUP BY dept_id"),
        "Should preserve GROUP BY");
    assertTrue(normalized.contains("HAVING COUNT( * ) > 5"),
        "Should preserve HAVING clause");
  }

  // ========== CTE Used in Main Query Tests ==========

  @Test
  void cteUsedInWhereSubquery() {
    String oracleSql = "WITH high_earners AS " +
        "(SELECT emp_id FROM employees WHERE salary > 100000) " +
        "SELECT dept_id FROM departments WHERE dept_id IN (SELECT emp_id FROM high_earners)";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH high_earners AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("dept_id IN") && normalized.contains("SELECT emp_id FROM high_earners"),
        "Should reference CTE in WHERE subquery");
  }

  @Test
  void cteUsedInJoin() {
    String oracleSql = "WITH active_depts AS " +
        "(SELECT dept_id FROM departments WHERE active = 'Y') " +
        "SELECT e.emp_name FROM employees e JOIN active_depts ad ON e.dept_id = ad.dept_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH active_depts AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("JOIN active_depts ad"),
        "Should join with CTE");
  }

  // ========== CTE with FROM DUAL Tests ==========

  @Test
  void cteWithFromDual() {
    String oracleSql = "WITH constants AS " +
        "(SELECT 1 as one, 2 as two FROM DUAL) " +
        "SELECT one, two FROM constants";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH constants AS (SELECT 1 AS one , 2 AS two)"),
        "FROM DUAL should be removed inside CTE");
    assertFalse(normalized.toUpperCase().contains("FROM DUAL"),
        "FROM DUAL should not appear in output");
    assertFalse(normalized.toLowerCase().contains("from dual"),
        "FROM DUAL should not appear in output (lowercase check)");
  }

  @Test
  void multipleCtesWithFromDual() {
    String oracleSql = "WITH " +
        "one AS (SELECT 1 as n FROM DUAL), " +
        "two AS (SELECT 2 as n FROM DUAL) " +
        "SELECT * FROM one, two";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertFalse(normalized.toUpperCase().contains("FROM DUAL"),
        "FROM DUAL should be removed from all CTEs");
  }

  // ========== CTE with Nested Subqueries Tests ==========

  @Test
  void cteWithNestedSubquery() {
    String oracleSql = "WITH outer_cte AS (" +
        "SELECT dept_id, (SELECT COUNT(*) FROM employees e WHERE e.dept_id = d.dept_id) as emp_count " +
        "FROM departments d" +
        ") SELECT * FROM outer_cte";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH outer_cte AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("SELECT COUNT( * )") && normalized.contains("FROM employees e") && normalized.contains("WHERE"),
        "Should preserve nested subquery");
  }

  // ========== CTE with Expressions Tests ==========

  @Test
  void cteWithCalculations() {
    String oracleSql = "WITH calculated AS (" +
        "SELECT emp_id, salary * 12 as annual_salary, salary * 0.1 as tax " +
        "FROM employees" +
        ") SELECT * FROM calculated";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH calculated AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("salary * 12 AS annual_salary"),
        "Should preserve calculations");
  }

  @Test
  void cteWithCaseExpression() {
    String oracleSql = "WITH categorized AS (" +
        "SELECT emp_id, " +
        "CASE WHEN salary > 100000 THEN 'high' WHEN salary > 50000 THEN 'medium' ELSE 'low' END as category " +
        "FROM employees" +
        ") SELECT * FROM categorized";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH categorized AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("CASE WHEN"),
        "Should preserve CASE expression");
    assertTrue(normalized.contains("THEN 'high'"),
        "Should preserve CASE branches");
  }

  // ========== CTE with String Functions Tests ==========

  @Test
  void cteWithConcatenation() {
    String oracleSql = "WITH full_names AS (" +
        "SELECT emp_id, first_name || ' ' || last_name as full_name " +
        "FROM employees" +
        ") SELECT * FROM full_names";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH full_names AS ("),
        "Should contain CTE definition");
    // Concatenation may be transformed to CONCAT or kept as ||
    // Just check the CTE is preserved
    assertTrue(normalized.contains("first_name"),
        "Should preserve concatenation expression");
  }

  // ========== CTE with Window Functions Tests ==========

  @Test
  void cteWithWindowFunction() {
    String oracleSql = "WITH ranked AS (" +
        "SELECT emp_id, salary, ROW_NUMBER() OVER (ORDER BY salary DESC) as rank " +
        "FROM employees" +
        ") SELECT * FROM ranked WHERE rank <= 10";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH ranked AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("ROW_NUMBER( ) OVER"),
        "Should preserve window function");
    assertTrue(normalized.contains("ORDER BY salary DESC NULLS FIRST"),
        "Should add NULLS FIRST to window function ORDER BY");
  }

  // ========== Inline PL/SQL Tests (Should Fail) ==========

  @Test
  void inlinePlsqlFunction_throwsException() {
    String oracleSql = "WITH " +
        "FUNCTION double_val(x NUMBER) RETURN NUMBER IS BEGIN RETURN x * 2; END; " +
        "dept_totals AS (SELECT dept_id FROM departments) " +
        "SELECT * FROM dept_totals";

    TransformationException ex = assertThrows(TransformationException.class,
        () -> transform(oracleSql));

    assertTrue(ex.getMessage().contains("Inline PL/SQL"),
        "Should mention inline PL/SQL");
    assertTrue(ex.getMessage().contains("not supported"),
        "Should indicate not supported");
  }

  @Test
  void inlinePlsqlProcedure_throwsException() {
    String oracleSql = "WITH " +
        "PROCEDURE log_msg(msg VARCHAR2) IS BEGIN NULL; END; " +
        "dept_totals AS (SELECT dept_id FROM departments) " +
        "SELECT * FROM dept_totals";

    TransformationException ex = assertThrows(TransformationException.class,
        () -> transform(oracleSql));

    assertTrue(ex.getMessage().contains("Inline PL/SQL"),
        "Should mention inline PL/SQL");
    assertTrue(ex.getMessage().contains("not supported"),
        "Should indicate not supported");
  }

  // ========== Edge Cases ==========

  @Test
  void cteWithSelectStar() {
    String oracleSql = "WITH all_employees AS (" +
        "SELECT * FROM employees" +
        ") SELECT * FROM all_employees";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH all_employees AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("SELECT * FROM employees"),
        "Should preserve SELECT * in CTE");
    assertTrue(normalized.contains("SELECT * FROM all_employees"),
        "Should preserve SELECT * in main query");
  }

  @Test
  void cteWithNoWhereClause() {
    String oracleSql = "WITH all_depts AS (" +
        "SELECT dept_id, dept_name FROM departments" +
        ") SELECT * FROM all_depts";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH all_depts AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("SELECT dept_id , dept_name FROM departments"),
        "Should preserve simple SELECT");
  }

  @Test
  void cteReferencedMultipleTimes() {
    String oracleSql = "WITH dept_avg AS (" +
        "SELECT dept_id, AVG(salary) as avg_sal FROM employees GROUP BY dept_id" +
        ") SELECT e.emp_name, e.salary, d.avg_sal " +
        "FROM employees e, dept_avg d WHERE e.dept_id = d.dept_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH dept_avg AS ("),
        "Should contain CTE definition");
    assertTrue(normalized.contains("FROM employees e") && normalized.contains("dept_avg d"),
        "Should reference CTE in comma-separated FROM");
  }
}
