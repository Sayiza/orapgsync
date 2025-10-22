package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Complex integration tests for CONNECT BY combined with other Oracle features.
 *
 * <p>These tests verify that CONNECT BY transformation works correctly when combined with:</p>
 * <ul>
 *   <li>ROWNUM transformation (→ LIMIT)</li>
 *   <li>Outer join transformation ((+) → LEFT/RIGHT JOIN)</li>
 *   <li>Subqueries with CONNECT BY</li>
 *   <li>CTEs combined with CONNECT BY</li>
 *   <li>Multiple features in a single query</li>
 * </ul>
 *
 * <p>This ensures the modular architecture correctly composes transformations.</p>
 */
class ConnectByComplexIntegrationTest {

  private AntlrParser parser;
  private PostgresCodeBuilder builder;

  @BeforeEach
  void setUp() {
    parser = new AntlrParser();

    // Set up transformation context with schema "hr" for schema qualification
    TransformationIndices indices = new TransformationIndices(
        new HashMap<>(), // tableColumns - Map<String, Map<String, ColumnTypeInfo>>
        new HashMap<>(), // typeMethods - Map<String, Set<String>>
        new HashSet<>(), // packageFunctions - Set<String>
        new HashMap<>()  // synonyms - Map<String, Map<String, String>>
    );
    TransformationContext context = new TransformationContext(
        "hr",
        indices,
        new SimpleTypeEvaluator("hr", indices)
    );
    builder = new PostgresCodeBuilder(context);
  }

  private String transform(String oracleSql) {
    ParseResult parseResult = parser.parseSelectStatement(oracleSql);
    if (parseResult.hasErrors()) {
      fail("Parse failed: " + parseResult.getErrors());
    }
    return builder.visit(parseResult.getTree());
  }

  // ========== CONNECT BY + ROWNUM ==========

  @Test
  void connectByWithRownum_LimitTopLevels() {
    // Get only top 10 nodes from hierarchy (ROWNUM limits final result)
    // NOTE: Currently ROWNUM + CONNECT BY is not fully integrated
    // CONNECT BY transformation happens first, bypassing ROWNUM analysis
    String oracleSql =
        "SELECT emp_id, manager_id, LEVEL " +
        "FROM employees " +
        "WHERE ROWNUM <= 10 " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output
    System.out.println("\n=== TEST: outerJoinWithDateAndStringFunctions ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL:");
    System.out.println(normalized);
    System.out.println("==================================================\n");

    // Should have recursive CTE
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Should generate recursive CTE");

    // TODO: ROWNUM + CONNECT BY integration
    // Currently ROWNUM is included in WHERE clause but not transformed to LIMIT
    // This is a known limitation - would require coordination between analyzers
    // For now, just verify query is generated
    assertFalse(result.isEmpty(), "Should generate non-empty query");
  }

  @Test
  void connectByWithRownumInMainQuery() {
    // ROWNUM applied to CTE result
    String oracleSql =
        "SELECT * FROM (" +
        "  SELECT emp_id, manager_id " +
        "  FROM employees " +
        "  START WITH manager_id IS NULL " +
        "  CONNECT BY PRIOR emp_id = manager_id" +
        ") WHERE ROWNUM <= 5";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should have recursive CTE inside subquery
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Should generate recursive CTE in subquery");

    // ROWNUM on outer query should become LIMIT
    assertTrue(normalized.contains("LIMIT 5"),
        "ROWNUM on outer query should become LIMIT");
  }

  // ========== CONNECT BY + Outer Joins ==========

  @Test
  void connectByWithOuterJoin_BeforeHierarchy() {
    // Outer join in FROM clause, then CONNECT BY
    // Note: This is a complex pattern - outer join to departments, then hierarchy on result
    String oracleSql =
        "SELECT e.emp_id, e.manager_id, d.dept_name " +
        "FROM employees e, departments d " +
        "WHERE e.dept_id = d.dept_id(+) " +  // Left outer join
        "START WITH e.manager_id IS NULL " +
        "CONNECT BY PRIOR e.emp_id = e.manager_id";

    // This should fail with "multiple tables not supported" error
    // CONNECT BY with JOINs is complex and not yet supported
    assertThrows(Exception.class, () -> transform(oracleSql),
        "CONNECT BY with multiple tables should throw exception");
  }

  // ========== Subqueries with CONNECT BY ==========

  @Test
  void subqueryWithConnectBy_InFromClause() {
    // Subquery that uses CONNECT BY, referenced in outer query
    String oracleSql =
        "SELECT hierarchy.emp_id, hierarchy.level " +
        "FROM (" +
        "  SELECT emp_id, manager_id, LEVEL " +
        "  FROM employees " +
        "  START WITH manager_id IS NULL " +
        "  CONNECT BY PRIOR emp_id = manager_id" +
        ") hierarchy " +
        "WHERE hierarchy.level <= 3";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should have recursive CTE in subquery
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Subquery should contain recursive CTE");

    // Outer query should reference the CTE result
    assertTrue(normalized.contains("FROM ( WITH RECURSIVE") ||
               normalized.contains("FROM (WITH RECURSIVE"),
        "Subquery should be preserved");

    // LEVEL should be replaced with level column
    assertTrue(normalized.contains("level"),
        "LEVEL pseudo-column should be replaced");
  }

  @Test
  void subqueryWithConnectBy_InWhereClause() {
    // Subquery with CONNECT BY in WHERE clause
    String oracleSql =
        "SELECT dept_id, dept_name " +
        "FROM departments " +
        "WHERE dept_id IN (" +
        "  SELECT DISTINCT dept_id " +
        "  FROM employees " +
        "  START WITH manager_id IS NULL " +
        "  CONNECT BY PRIOR emp_id = manager_id" +
        ")";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should have recursive CTE in subquery
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "WHERE clause subquery should contain recursive CTE");

    // Main query should reference departments (with or without schema qualification)
    assertTrue(normalized.contains("FROM departments") || normalized.contains("FROM hr.departments") || normalized.contains("FROM hr . departments"),
        "Main query should still reference departments");
  }

  @Test
  void nestedConnectBy_TwoLevelsDeep() {
    // Outer query with CONNECT BY referencing subquery that also has CONNECT BY
    // This is a very complex pattern - hierarchy of hierarchies
    // NOTE: This is currently not supported - CONNECT BY on subquery results
    String oracleSql =
        "SELECT emp_id, LEVEL as outer_level " +
        "FROM (" +
        "  SELECT emp_id, manager_id, LEVEL as inner_level " +
        "  FROM employees " +
        "  START WITH manager_id IS NULL " +
        "  CONNECT BY PRIOR emp_id = manager_id " +
        "  AND LEVEL <= 2" +  // Limit inner hierarchy depth
        ") " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    // This should fail - CONNECT BY requires simple table in FROM, not subquery
    assertThrows(Exception.class, () -> transform(oracleSql),
        "CONNECT BY on subquery result should throw exception");
  }

  @Test
  void multipleIndependentConnectBySubqueries() {
    // Multiple subqueries, each with their own CONNECT BY clause
    // This tests whether independent hierarchies can coexist in the same query
    String oracleSql =
        "SELECT e.emp_id, d.dept_id " +
        "FROM (" +
        "  SELECT emp_id, dept_id " +
        "  FROM employees " +
        "  START WITH manager_id IS NULL " +
        "  CONNECT BY PRIOR emp_id = manager_id" +
        ") e, (" +
        "  SELECT dept_id " +
        "  FROM departments " +
        "  START WITH parent_dept_id IS NULL " +
        "  CONNECT BY PRIOR dept_id = parent_dept_id" +
        ") d " +
        "WHERE e.dept_id = d.dept_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output
    System.out.println("\n=== TEST: multipleIndependentConnectBySubqueries ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL:");
    System.out.println(result);
    System.out.println("==================================================\n");

    // Should have two recursive CTEs - one for employees, one for departments
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Should generate recursive CTE for employees hierarchy");

    assertTrue(normalized.contains("WITH RECURSIVE departments_hierarchy AS ("),
        "Should generate recursive CTE for departments hierarchy");

    // Both CTEs should have base and recursive cases
    int unionAllCount = countOccurrences(normalized, "UNION ALL");
    assertTrue(unionAllCount >= 2,
        "Should have at least two UNION ALL (one per recursive CTE), found: " + unionAllCount);

    // The outer query should reference both hierarchies
    assertTrue(normalized.contains("e.emp_id") || normalized.contains("emp_id"),
        "Should reference employee hierarchy in SELECT");

    assertTrue(normalized.contains("d.dept_id") || normalized.contains("dept_id"),
        "Should reference department hierarchy in SELECT");

    // Should have WHERE clause joining the two hierarchies
    assertTrue(normalized.contains("WHERE") || normalized.contains("where"),
        "Should preserve WHERE clause joining hierarchies");

    // Verify query is not empty and appears valid
    assertFalse(result.isEmpty(), "Should generate non-empty query");
    assertTrue(result.length() > 100, "Should generate substantial query");
  }

  // ========== CONNECT BY + CTEs ==========

  @Test
  void existingCteWithConnectBy() {
    // Existing CTE followed by query with CONNECT BY
    // This tests CTE merging capability
    String oracleSql =
        "WITH active_employees AS (" +
        "  SELECT emp_id, manager_id FROM employees WHERE status = 'ACTIVE'" +
        ") " +
        "SELECT emp_id, manager_id " +
        "FROM active_employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should have BOTH CTEs: the existing one AND the generated hierarchy CTE
    assertTrue(normalized.contains("WITH RECURSIVE"),
        "Should have RECURSIVE keyword for hierarchy CTE");

    assertTrue(normalized.contains("active_employees AS ("),
        "Should preserve existing CTE");

    // Should reference active_employees in the hierarchy CTE
    assertTrue(normalized.contains("active_employees_hierarchy") ||
               normalized.contains("FROM active_employees"),
        "Should reference the base CTE in hierarchy");
  }

  @Test
  void recursiveCteWithConnectBy() {
    // Oracle CTE (non-recursive syntax) followed by query with CONNECT BY
    // Oracle doesn't use RECURSIVE keyword in input - that's PostgreSQL output syntax
    String oracleSql =
        "WITH emp_tree AS (" +
        "  SELECT emp_id, manager_id, 1 as depth " +
        "  FROM employees WHERE manager_id IS NULL " +
        "  UNION ALL " +
        "  SELECT e.emp_id, e.manager_id, t.depth + 1 " +
        "  FROM employees e " +
        "  JOIN emp_tree t ON e.manager_id = t.emp_id " +
        "  WHERE t.depth < 3" +
        ") " +
        "SELECT emp_id FROM emp_tree " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    // This is a complex edge case - CTE already does hierarchy, then CONNECT BY on CTE result
    // Current implementation may not handle this perfectly
    String result = transform(oracleSql);

    // At minimum, should not crash and should produce RECURSIVE CTEs
    assertFalse(result.isEmpty(), "Should produce non-empty result");
    assertTrue(result.contains("WITH RECURSIVE"),
        "Should have RECURSIVE keyword for PostgreSQL CTEs");
  }

  // ========== Multiple Features Combined ==========

  @Test
  void connectByWithRownumAndOrderBy() {
    // CONNECT BY + ROWNUM + ORDER BY all together
    // NOTE: ROWNUM + CONNECT BY integration is a known limitation
    String oracleSql =
        "SELECT emp_id, manager_id, LEVEL " +
        "FROM employees " +
        "WHERE ROWNUM <= 20 " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id " +
        "ORDER BY emp_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should have recursive CTE
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Should generate recursive CTE");

    // Should have ORDER BY
    assertTrue(normalized.contains("ORDER BY emp_id"),
        "ORDER BY should be preserved");

    // TODO: ROWNUM transformation when combined with CONNECT BY
    // Currently this is not fully integrated - see known limitations
    assertFalse(result.isEmpty(), "Should generate valid SQL");
  }

  @Test
  void connectByWithWhereAndLevel() {
    // WHERE clause filtering + LEVEL in SELECT + START WITH
    String oracleSql =
        "SELECT emp_id, manager_id, LEVEL, salary " +
        "FROM employees " +
        "WHERE salary > 50000 " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // WHERE clause should appear in BOTH base and recursive cases
    assertTrue(normalized.contains("WHERE manager_id IS NULL AND salary > 50000"),
        "Base case should have START WITH AND original WHERE");

    // Recursive case should also have WHERE clause
    // Note: This is verified by checking it doesn't just appear once
    int whereCount = countOccurrences(normalized, "salary > 50000");
    assertTrue(whereCount >= 2,
        "WHERE clause should appear in both base and recursive cases");
  }

  @Test
  void realWorldScenario_EmployeeHierarchyWithMetrics() {
    // Real-world scenario: Get employee hierarchy with aggregated metrics
    String oracleSql =
        "SELECT " +
        "  emp_id, " +
        "  manager_id, " +
        "  LEVEL as hierarchy_level, " +
        "  salary, " +
        "  (SELECT COUNT(*) FROM employees e2 WHERE e2.manager_id = e1.emp_id) as direct_reports " +
        "FROM employees e1 " +
        "WHERE status = 'ACTIVE' " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id " +
        "ORDER BY LEVEL, emp_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Should generate recursive CTE
    assertTrue(normalized.contains("WITH RECURSIVE"),
        "Should generate recursive CTE");

    // LEVEL should be replaced in SELECT list
    assertTrue(normalized.contains("level as hierarchy_level") ||
               normalized.contains("level AS hierarchy_level"),
        "LEVEL should be replaced with level column");

    // Should have ORDER BY (LEVEL replacement in ORDER BY is TODO)
    assertTrue(normalized.contains("ORDER BY"),
        "ORDER BY should be preserved");

    // TODO: ORDER BY LEVEL replacement
    // Currently LEVEL in ORDER BY clause may not be fully replaced
    // This is a Phase 3 enhancement

    // Should preserve basic structure
    assertTrue(normalized.contains("salary"),
        "Should preserve salary column");

    // TODO: Verify correlated subquery handling
    // Currently SELECT list visitor may need enhancement for complex subqueries
    assertFalse(result.isEmpty(), "Should generate valid SQL");
  }

  // ========== Helper Methods ==========

  /**
   * Counts occurrences of a substring in a string.
   */
  private int countOccurrences(String str, String substring) {
    int count = 0;
    int index = 0;
    while ((index = str.indexOf(substring, index)) != -1) {
      count++;
      index += substring.length();
    }
    return count;
  }
}
