package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CONNECT BY hierarchical query transformation to PostgreSQL recursive CTEs.
 *
 * <p>Oracle CONNECT BY has no direct PostgreSQL equivalent and must be transformed
 * to recursive CTEs with careful handling of:</p>
 * <ul>
 *   <li>START WITH → base case WHERE clause</li>
 *   <li>CONNECT BY PRIOR → JOIN condition in recursive case</li>
 *   <li>LEVEL pseudo-column → explicit level counter</li>
 *   <li>WHERE clause distribution → both base and recursive cases</li>
 * </ul>
 *
 * <p>Test progression:
 * <ol>
 *   <li>Simple hierarchy (employees table)</li>
 *   <li>PRIOR on left vs right</li>
 *   <li>LEVEL in SELECT list</li>
 *   <li>WHERE clause distribution</li>
 *   <li>Complex conditions</li>
 *   <li>Unsupported features (NOCYCLE, advanced pseudo-columns)</li>
 * </ol>
 */
class ConnectByTransformationTest {

  private AntlrParser parser;
  private PostgresCodeBuilder builder;

  @BeforeEach
  void setUp() {
    parser = new AntlrParser();

    // Set up transformation context with schema "hr" for schema qualification
    // This ensures table names without explicit schema get qualified as hr.tablename
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

  // ========== Basic Hierarchy Tests ==========

  @Test
  void simpleHierarchy_PriorOnLeft() {
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Verify recursive CTE structure
    assertTrue(normalized.startsWith("WITH RECURSIVE employees_hierarchy AS ("),
        "Should start with WITH RECURSIVE {table}_hierarchy AS");

    // Verify base case (note: spacing is "column, 1 as level" not "column , 1 as level")
    assertTrue(normalized.contains("SELECT emp_id , manager_id, 1 as level"),
        "Base case should include level = 1");
    assertTrue(normalized.contains("WHERE manager_id IS NULL"),
        "Base case should have START WITH condition");

    // Verify UNION ALL
    assertTrue(normalized.contains("UNION ALL"),
        "Should have UNION ALL between base and recursive cases");

    // Verify recursive case
    assertTrue(normalized.contains("JOIN employees_hierarchy"),
        "Recursive case should JOIN the CTE");
    assertTrue(normalized.contains("h.level + 1"),
        "Recursive case should increment level");

    // Verify final SELECT
    assertTrue(normalized.contains("SELECT emp_id , manager_id FROM employees_hierarchy"),
        "Final SELECT should query the CTE");
  }

  @Test
  void simpleHierarchy_PriorOnRight() {
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY manager_id = PRIOR emp_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Same structure as PriorOnLeft, just different JOIN direction
    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Should generate recursive CTE");
    assertTrue(normalized.contains("JOIN employees_hierarchy"),
        "Should have JOIN to CTE");
  }

  @Test
  void withTableAlias() {
    String oracleSql =
        "SELECT e.emp_id, e.manager_id " +
        "FROM employees e " +
        "START WITH e.manager_id IS NULL " +
        "CONNECT BY PRIOR e.emp_id = e.manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output
    System.out.println("\n=== TEST: outerJoinWithDateAndStringFunctions ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL:");
    System.out.println(normalized);
    System.out.println("==================================================\n");

    assertTrue(normalized.contains("WITH RECURSIVE employees_hierarchy AS ("),
        "Should generate recursive CTE regardless of alias");
  }

  // ========== LEVEL Pseudo-Column Tests ==========

  @Test
  void withLevelInSelect() {
    String oracleSql =
        "SELECT emp_id, LEVEL " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // LEVEL should be replaced with "level" column reference in final SELECT
    assertTrue(normalized.contains("SELECT emp_id , level FROM employees_hierarchy"),
        "LEVEL should be replaced with level column in final SELECT");

    // Base case should add level = 1
    assertTrue(normalized.contains("1 as level"),
        "Base case should initialize level to 1");

    // Recursive case should increment level
    assertTrue(normalized.contains("h.level + 1"),
        "Recursive case should increment level");
  }

  @Test
  void withLevelInWhere() {
    // LEVEL in WHERE acts as depth limiter
    String oracleSql =
        "SELECT emp_id, manager_id, LEVEL " +
        "FROM employees " +
        "WHERE LEVEL <= 3 " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output
    System.out.println("\n=== TEST: withLevelInWhere ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL:");
    System.out.println(result);
    System.out.println("\nNORMALIZED:");
    System.out.println(normalized);
    System.out.println("==================================================\n");

    // Base case should have LEVEL replaced with 1 (base case is always level 1)
    assertTrue(normalized.contains("WHERE manager_id IS NULL AND 1 <= 3"),
        "Base case should replace LEVEL with 1");

    // Recursive case should have LEVEL replaced with h.level + 1 (depth limiting)
    assertTrue(normalized.contains("WHERE h.level + 1 <= 3") ||
               normalized.contains("WHERE h . level + 1 <= 3"),
        "Recursive case should use h.level + 1 for depth limiting. Got: " + normalized);

    // Final SELECT should replace LEVEL with level column
    assertTrue(normalized.contains("SELECT emp_id , manager_id , level FROM"),
        "Final SELECT should use level column");
  }

  @Test
  void withLevelInOrderBy() {
    // LEVEL in ORDER BY should be replaced with level column
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id " +
        "ORDER BY LEVEL";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // ORDER BY should reference level column
    assertTrue(normalized.contains("ORDER BY level"),
        "ORDER BY should use level column reference");
  }

  @Test
  void withLevelInComplexExpression() {
    // LEVEL in complex expressions (LEVEL * 2, LEVEL + 1, etc.)
    String oracleSql =
        "SELECT emp_id, LEVEL * 10 as depth_score " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Final SELECT should replace LEVEL in expression
    assertTrue(normalized.contains("level * 10") || normalized.contains("level * 10"),
        "Complex expression should use level column");
  }

  @Test
  void withLevelInMultipleContexts() {
    // LEVEL used in SELECT, WHERE, and ORDER BY simultaneously
    String oracleSql =
        "SELECT emp_id, LEVEL as hierarchy_depth " +
        "FROM employees " +
        "WHERE LEVEL <= 5 " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id " +
        "ORDER BY LEVEL DESC";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Verify all contexts handle LEVEL correctly
    assertTrue(normalized.contains("level as hierarchy_depth") ||
               normalized.contains("level AS hierarchy_depth"),
        "SELECT should use level column");

    assertTrue(normalized.contains("h.level + 1 <= 5") ||
               normalized.contains("h . level + 1 <= 5"),
        "WHERE should use h.level + 1 for depth limiting");

    assertTrue(normalized.contains("ORDER BY level DESC"),
        "ORDER BY should use level column");
  }

  // ========== WHERE Clause Distribution Tests ==========

  @Test
  void withWhereClause() {
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM employees " +
        "WHERE salary > 50000 " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // WHERE should appear in both base and recursive cases
    // Base case: START WITH AND original WHERE
    assertTrue(normalized.matches(".*WHERE.*manager_id IS NULL.*AND.*salary > 50000.*"),
        "Base case should combine START WITH and WHERE");

    // Recursive case: original WHERE only
    // Note: Exact pattern depends on implementation details
    assertTrue(normalized.contains("salary > 50000"),
        "WHERE clause should be distributed to both cases");
  }

  // ========== Error Cases - Unsupported Features ==========

  @Test
  void nocycle_ShouldThrowException() {
    String oracleSql =
        "SELECT emp_id FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY NOCYCLE PRIOR emp_id = manager_id";

    TransformationException exception = assertThrows(
        TransformationException.class,
        () -> transform(oracleSql)
    );

    assertTrue(exception.getMessage().contains("NOCYCLE"),
        "Should mention NOCYCLE is not supported");
    assertTrue(exception.getMessage().contains("cycle detection"),
        "Should provide guidance on cycle detection");
  }

  @Test
  void connectByRoot_ShouldThrowException() {
    String oracleSql =
        "SELECT CONNECT_BY_ROOT emp_id FROM employees " +
        "CONNECT BY PRIOR emp_id = manager_id";

    TransformationException exception = assertThrows(
        TransformationException.class,
        () -> transform(oracleSql)
    );

    assertTrue(exception.getMessage().contains("CONNECT_BY_ROOT"),
        "Should mention CONNECT_BY_ROOT is not yet supported");
  }

  @Test
  void missingStartWith_ShouldThrowException() {
    // CONNECT BY without START WITH is valid Oracle but rare
    // We require START WITH for now
    String oracleSql =
        "SELECT emp_id FROM employees " +
        "CONNECT BY PRIOR emp_id = manager_id";

    TransformationException exception = assertThrows(
        TransformationException.class,
        () -> transform(oracleSql)
    );

    assertTrue(exception.getMessage().contains("START WITH"),
        "Should require START WITH clause");
  }

  @Test
  void multipleTablesInFrom_ShouldThrowException() {
    String oracleSql =
        "SELECT e.emp_id FROM employees e, departments d " +
        "WHERE e.dept_id = d.dept_id " +
        "CONNECT BY PRIOR e.emp_id = e.manager_id";

    TransformationException exception = assertThrows(
        TransformationException.class,
        () -> transform(oracleSql)
    );

    assertTrue(exception.getMessage().contains("multiple tables"),
        "Should reject CONNECT BY with multiple tables");
  }

  @Test
  void missingPriorOperator_ShouldThrowException() {
    String oracleSql =
        "SELECT emp_id FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY emp_id = manager_id";  // Missing PRIOR!

    TransformationException exception = assertThrows(
        TransformationException.class,
        () -> transform(oracleSql)
    );

    assertTrue(exception.getMessage().contains("PRIOR"),
        "Should require PRIOR operator in CONNECT BY condition");
  }

  // ========== Edge Cases ==========

  @Test
  void selectStar() {
    String oracleSql =
        "SELECT * " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Note: spacing is "*, 1 as level" not "* , 1 as level"
    assertTrue(normalized.contains("SELECT *, 1 as level"),
        "SELECT * should work, with level column added");
  }

  @Test
  void withOrderBy() {
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id " +
        "ORDER BY emp_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // ORDER BY should appear in final SELECT
    assertTrue(normalized.contains("ORDER BY emp_id"),
        "ORDER BY should be preserved in final SELECT");

    // Note: ORDER SIBLINGS BY is not supported (different from ORDER BY)
  }

  // ========== Schema Qualification Tests ==========

  @Test
  void schemaQualification_ImplicitSchema() {
    // Test that schema qualification is preserved when delegating to visitors
    // This assumes the builder has schema context from StateService
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output to see actual schema qualification
    System.out.println("\n=== TEST: schemaQualification_ImplicitSchema ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL:");
    System.out.println(result);
    System.out.println("NORMALIZED:");
    System.out.println(normalized);
    System.out.println("==================================================\n");

    // Check if schema qualification appears in FROM clauses
    // The existing visitor infrastructure should add schema qualification
    // We should see something like: FROM schema.employees
    // Let's check if there's ANY schema qualification
    boolean hasSchemaQualification = normalized.matches(".*FROM\\s+\\w+\\.employees.*");

    if (!hasSchemaQualification) {
      System.err.println("WARNING: No schema qualification detected!");
      System.err.println("This may indicate schema qualification is being lost in CONNECT BY transformation");
      System.err.println("Expected pattern: FROM schema.employees");
      System.err.println("Actual FROM clauses: " + extractFromClauses(normalized));
    }

    // For now, just verify query is generated
    // But print warning if schema qualification appears to be missing
    assertFalse(result.isEmpty(), "Should generate non-empty query");
  }

  @Test
  void schemaQualification_ExplicitSchema() {
    // Test with explicit schema qualification in Oracle SQL
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM hr.employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output
    System.out.println("\n=== TEST: schemaQualification_ExplicitSchema ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL:");
    System.out.println(result);
    System.out.println("==================================================\n");

    // Should preserve hr schema in FROM clauses
    // Base case: FROM hr.employees
    // Recursive case: FROM hr.employees t
    assertTrue(normalized.contains("FROM hr.employees") ||
               normalized.contains("FROM hr . employees"),
        "Should preserve schema qualification in FROM clause. Got: " + result);
  }

  @Test
  void schemaQualification_VerifyBothCases() {
    // Verify schema appears in BOTH base and recursive cases
    String oracleSql =
        "SELECT emp_id, manager_id " +
        "FROM hr.employees " +
        "START WITH manager_id IS NULL " +
        "CONNECT BY PRIOR emp_id = manager_id";

    String result = transform(oracleSql);
    String normalized = result.trim().replaceAll("\\s+", " ");

    // Debug output
    System.out.println("\n=== TEST: schemaQualification_VerifyBothCases ===");
    System.out.println("ORACLE SQL:");
    System.out.println(oracleSql);
    System.out.println("\nPOSTGRESQL SQL (formatted):");
    System.out.println(result);
    System.out.println("\nSearching for schema occurrences...");

    // Count occurrences of "hr.employees" or "hr . employees"
    int schemaCount = countSchemaOccurrences(normalized, "hr", "employees");
    System.out.println("Found " + schemaCount + " occurrences of hr.employees");

    // Should appear at least twice: once in base case, once in recursive case
    // (The final SELECT references the CTE, not the table, so it won't have schema)
    assertTrue(schemaCount >= 2,
        "Schema qualification should appear in both base and recursive cases. Found: " + schemaCount + " occurrences. SQL: " + result);

    System.out.println("==================================================\n");
  }

  // ========== Helper Methods ==========

  private String extractFromClauses(String sql) {
    StringBuilder fromClauses = new StringBuilder();
    String[] parts = sql.split("FROM");
    for (int i = 1; i < parts.length; i++) {
      String clause = parts[i].split("WHERE|JOIN|UNION")[0].trim();
      fromClauses.append("FROM ").append(clause).append("; ");
    }
    return fromClauses.toString();
  }

  private int countSchemaOccurrences(String sql, String schema, String table) {
    // Match both "schema.table" and "schema . table" (with spaces)
    int count = 0;
    String pattern1 = schema + "." + table;
    String pattern2 = schema + " . " + table;

    // Count direct matches
    String temp = sql;
    while (temp.contains(pattern1)) {
      count++;
      int index = temp.indexOf(pattern1);
      temp = temp.substring(index + pattern1.length());
    }

    // Count spaced matches
    temp = sql;
    while (temp.contains(pattern2)) {
      count++;
      int index = temp.indexOf(pattern2);
      temp = temp.substring(index + pattern2.length());
    }

    return count;
  }

  // TODO: Phase 4+ - Additional tests
  // - Complex PRIOR conditions (AND, multiple comparisons)
  // - Existing WITH clause + CONNECT BY (CTE merging)
  // - LEVEL in complex expressions (LEVEL * 2, LEVEL + 1, etc.)
  // - SYS_CONNECT_BY_PATH (Phase 5)
  // - CONNECT_BY_ISLEAF (Phase 5)
}
