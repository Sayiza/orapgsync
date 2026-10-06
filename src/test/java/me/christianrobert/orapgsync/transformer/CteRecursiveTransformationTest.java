package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for recursive CTE transformation.
 *
 * <p>Key difference from Oracle to PostgreSQL:
 * <ul>
 *   <li>Oracle: WITH emp_tree AS (... recursive query ...) - no RECURSIVE keyword</li>
 *   <li>PostgreSQL: WITH RECURSIVE emp_tree AS (... recursive query ...) - RECURSIVE keyword required</li>
 * </ul>
 *
 * <p>Detection strategy:
 * <ul>
 *   <li>A CTE is recursive if it references itself in its subquery definition</li>
 *   <li>If ANY CTE in a WITH clause is recursive, add RECURSIVE keyword</li>
 *   <li>This handles both self-recursive and mutually recursive CTEs</li>
 * </ul>
 *
 * <p>Test coverage:
 * <ol>
 *   <li>Simple recursive CTE (employee hierarchy)</li>
 *   <li>Recursive CTE with column list</li>
 *   <li>Multiple CTEs, one recursive</li>
 *   <li>Multiple CTEs, all non-recursive (should not add RECURSIVE)</li>
 *   <li>Recursive CTE with UNION (not UNION ALL)</li>
 *   <li>Number generation example</li>
 *   <li>Recursive CTE with qualified self-reference (schema.cte_name)</li>
 *   <li>Recursive CTE with complex WHERE clause</li>
 *   <li>Recursive CTE with JOIN</li>
 * </ol>
 */
class CteRecursiveTransformationTest {

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

  // ========== Simple Recursive CTE Tests ==========

  @Test
  void simpleRecursiveCte_employeeHierarchy() {
    String oracleSql = "WITH emp_tree AS (" +
        "SELECT emp_id, mgr_id, 1 AS lvl FROM employees WHERE mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.mgr_id, t.lvl+1 FROM employees e JOIN emp_tree t ON e.mgr_id = t.emp_id" +
        ") SELECT * FROM emp_tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH RECURSIVE emp_tree AS ("),
        "Should start with WITH RECURSIVE for recursive CTE");
    assertTrue(normalized.contains("UNION ALL"),
        "Should preserve UNION ALL");
    assertTrue(normalized.contains("FROM employees e JOIN emp_tree t"),
        "Should preserve self-reference in recursive part");
  }

  @Test
  void simpleRecursiveCte_numberGeneration() {
    String oracleSql = "WITH numbers AS (" +
        "SELECT 1 AS n FROM DUAL " +
        "UNION ALL " +
        "SELECT n + 1 FROM numbers WHERE n < 100" +
        ") SELECT * FROM numbers";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH RECURSIVE numbers AS ("),
        "Should start with WITH RECURSIVE");
    assertTrue(normalized.contains("FROM numbers WHERE n < 100"),
        "Should preserve self-reference");
    // FROM DUAL should be removed
    assertFalse(normalized.toUpperCase().contains("FROM DUAL"),
        "FROM DUAL should be removed");
  }

  // ========== Recursive CTE with Column List ==========

  @Test
  void recursiveCteWithColumnList() {
    String oracleSql = "WITH emp_tree (emp_id, mgr_id, lvl) AS (" +
        "SELECT emp_id, mgr_id, 1 FROM employees WHERE mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.mgr_id, t.lvl+1 FROM employees e JOIN emp_tree t ON e.mgr_id = t.emp_id" +
        ") SELECT * FROM emp_tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH RECURSIVE emp_tree (emp_id,mgr_id,lvl) AS ("),
        "Should start with WITH RECURSIVE and preserve column list");
  }

  // ========== Multiple CTEs - Mixed Recursive and Non-Recursive ==========

  @Test
  void multipleCtes_oneRecursive() {
    String oracleSql = "WITH " +
        "base AS (SELECT emp_id FROM employees WHERE dept_id = 10), " +
        "tree AS (SELECT emp_id FROM base UNION ALL SELECT e.emp_id FROM employees e JOIN tree t ON e.mgr_id = t.emp_id) " +
        "SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should add RECURSIVE because second CTE (tree) is recursive
    assertTrue(normalized.startsWith("WITH RECURSIVE base AS ("),
        "Should add RECURSIVE when any CTE is recursive");
    assertTrue(normalized.contains(", tree AS ("),
        "Should contain second CTE");
  }

  @Test
  void multipleCtes_firstRecursiveSecondNot() {
    String oracleSql = "WITH " +
        "tree AS (SELECT 1 AS n FROM DUAL UNION ALL SELECT n+1 FROM tree WHERE n < 5), " +
        "filtered AS (SELECT n FROM tree WHERE n > 2) " +
        "SELECT * FROM filtered";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE when first CTE is recursive");
    assertTrue(normalized.contains(", filtered AS ("),
        "Should contain second CTE");
  }

  @Test
  void multipleCtes_allNonRecursive_noRecursiveKeyword() {
    String oracleSql = "WITH " +
        "dept_totals AS (SELECT dept_id, COUNT(*) AS cnt FROM departments GROUP BY dept_id), " +
        "high_count AS (SELECT dept_id FROM dept_totals WHERE cnt > 10) " +
        "SELECT * FROM high_count";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH dept_totals AS ("),
        "Should NOT add RECURSIVE when no CTE is recursive");
    assertFalse(normalized.contains("RECURSIVE"),
        "Should not contain RECURSIVE keyword");
  }

  // ========== Recursive CTE with UNION (not UNION ALL) ==========

  @Test
  void recursiveCteWithUnion_notUnionAll() {
    // Oracle allows UNION (removes duplicates) in recursive CTEs
    // PostgreSQL also allows this
    String oracleSql = "WITH tree AS (" +
        "SELECT 1 AS n FROM DUAL " +
        "UNION " +
        "SELECT n+1 FROM tree WHERE n < 10" +
        ") SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE even with UNION (not UNION ALL)");
    assertTrue(normalized.contains("UNION SELECT"),
        "Should preserve UNION");
  }

  // ========== Recursive CTE with Qualified Self-Reference ==========

  @Test
  void recursiveCte_withSchemaQualifiedSelfReference() {
    // Sometimes Oracle qualifies CTE references with schema (though unusual for CTEs)
    // Our analyzer should detect this as recursive
    String oracleSql = "WITH tree AS (" +
        "SELECT 1 AS n FROM DUAL " +
        "UNION ALL " +
        "SELECT n+1 FROM hr.tree WHERE n < 5" +
        ") SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should detect recursion even with schema prefix
    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should detect recursion even with schema-qualified reference");
  }

  // ========== Recursive CTE with Complex Queries ==========

  @Test
  void recursiveCteWithComplexWhere() {
    String oracleSql = "WITH tree AS (" +
        "SELECT emp_id, mgr_id, salary FROM employees WHERE mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.mgr_id, e.salary FROM employees e " +
        "JOIN tree t ON e.mgr_id = t.emp_id " +
        "WHERE e.salary > t.salary AND e.dept_id IN (10, 20, 30)" +
        ") SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("e . salary > t . salary"),
        "Should preserve complex WHERE conditions");
    assertTrue(normalized.contains("dept_id IN") && normalized.contains("10, 20, 30"),
        "Should preserve IN clause");
  }

  @Test
  void recursiveCteWithMultipleJoins() {
    String oracleSql = "WITH org_chart AS (" +
        "SELECT e.emp_id, e.emp_name, d.dept_name, 1 AS level " +
        "FROM employees e JOIN departments d ON e.dept_id = d.dept_id " +
        "WHERE e.mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.emp_name, d.dept_name, oc.level + 1 " +
        "FROM employees e " +
        "JOIN departments d ON e.dept_id = d.dept_id " +
        "JOIN org_chart oc ON e.mgr_id = oc.emp_id" +
        ") SELECT * FROM org_chart";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE org_chart AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("JOIN departments d"),
        "Should preserve JOIN with departments");
    assertTrue(normalized.contains("JOIN org_chart oc"),
        "Should preserve recursive JOIN");
  }

  // ========== Recursive CTE with GROUP BY and Aggregation ==========

  @Test
  void recursiveCteWithAggregation() {
    String oracleSql = "WITH hierarchy AS (" +
        "SELECT dept_id, dept_name, parent_dept_id, 1 AS lvl, 1 AS dept_count " +
        "FROM departments WHERE parent_dept_id IS NULL " +
        "UNION ALL " +
        "SELECT d.dept_id, d.dept_name, d.parent_dept_id, h.lvl + 1, h.dept_count + 1 " +
        "FROM departments d JOIN hierarchy h ON d.parent_dept_id = h.dept_id" +
        ") SELECT lvl, COUNT(*) AS count_at_level FROM hierarchy GROUP BY lvl";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE hierarchy AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("GROUP BY lvl"),
        "Should preserve GROUP BY in main query");
  }

  // ========== Edge Cases ==========

  @Test
  void recursiveCteWithOrderBy() {
    String oracleSql = "WITH tree AS (" +
        "SELECT 1 AS n, 'root' AS label FROM DUAL " +
        "UNION ALL " +
        "SELECT n + 1, 'level_' || (n + 1) FROM tree WHERE n < 5" +
        ") SELECT * FROM tree ORDER BY n DESC";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("ORDER BY n DESC NULLS FIRST"),
        "Should add NULLS FIRST to ORDER BY");
  }

  @Test
  void recursiveCteReferencedInMultiplePlaces() {
    String oracleSql = "WITH tree AS (" +
        "SELECT 1 AS n FROM DUAL " +
        "UNION ALL " +
        "SELECT n + 1 FROM tree WHERE n < 5" +
        ") SELECT t1.n, t2.n AS n2 FROM tree t1 CROSS JOIN tree t2 WHERE t1.n < t2.n";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("FROM tree t1"),
        "Should preserve first reference");
    assertTrue(normalized.contains("CROSS JOIN tree t2"),
        "Should preserve second reference");
  }

  @Test
  void recursiveCteWithCaseExpression() {
    String oracleSql = "WITH tree AS (" +
        "SELECT emp_id, mgr_id, salary, CASE WHEN mgr_id IS NULL THEN 'CEO' ELSE 'Employee' END AS role " +
        "FROM employees WHERE mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.mgr_id, e.salary, CASE WHEN e.mgr_id IN (SELECT emp_id FROM tree) THEN 'Manager' ELSE 'Employee' END " +
        "FROM employees e JOIN tree t ON e.mgr_id = t.emp_id" +
        ") SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("CASE WHEN"),
        "Should preserve CASE expression");
  }

  @Test
  void recursiveCteWithSubqueryInSelect() {
    String oracleSql = "WITH tree AS (" +
        "SELECT emp_id, mgr_id, (SELECT COUNT(*) FROM employees WHERE mgr_id = tree.emp_id) AS subordinate_count " +
        "FROM employees WHERE mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.mgr_id, (SELECT COUNT(*) FROM employees WHERE mgr_id = e.emp_id) " +
        "FROM employees e JOIN tree t ON e.mgr_id = t.emp_id" +
        ") SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.contains("WITH RECURSIVE tree AS ("),
        "Should add RECURSIVE");
    assertTrue(normalized.contains("SELECT COUNT( * )"),
        "Should preserve correlated subquery");
  }

  @Test
  void recursiveCteMaxDepthLimitation() {
    // Real-world scenario: limiting recursion depth
    String oracleSql = "WITH tree (emp_id, mgr_id, lvl) AS (" +
        "SELECT emp_id, mgr_id, 1 FROM employees WHERE mgr_id IS NULL " +
        "UNION ALL " +
        "SELECT e.emp_id, e.mgr_id, t.lvl + 1 FROM employees e JOIN tree t ON e.mgr_id = t.emp_id WHERE t.lvl < 10" +
        ") SELECT * FROM tree";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    assertTrue(normalized.startsWith("WITH RECURSIVE tree (emp_id,mgr_id,lvl) AS ("),
        "Should add RECURSIVE with column list");
    assertTrue(normalized.contains("WHERE t . lvl < 10"),
        "Should preserve depth limitation");
  }
}
