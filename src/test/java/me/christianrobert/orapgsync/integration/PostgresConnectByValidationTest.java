package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for Oracle CONNECT BY → PostgreSQL CTE transformation.
 *
 * <p><b>Philosophy</b>: Each test validates multiple aspects of the transformation in a single
 * comprehensive test rather than splitting into many micro-tests. This approach better reflects
 * real-world usage and catches integration issues between features.
 *
 * <p><b>What we validate</b>:
 * <ul>
 *   <li><b>SQL correctness</b>: Transformed SQL executes without errors</li>
 *   <li><b>Result correctness</b>: Returns expected rows in correct order</li>
 *   <li><b>Hierarchy semantics</b>: LEVEL values, parent-child relationships preserved</li>
 *   <li><b>WHERE clause behavior</b>: Filtering works in both base and recursive cases</li>
 *   <li><b>ORDER BY</b>: Hierarchical ordering and ORDER SIBLINGS BY work correctly</li>
 * </ul>
 *
 * <p><b>Test Data</b>: Uses a realistic employee hierarchy:
 * <pre>
 * CEO (1)
 * ├── VP Sales (2)
 * │   └── Sales Manager (4)
 * │       └── Sales Rep (7)
 * └── VP Engineering (3)
 *     ├── Engineer 1 (5)
 *     └── Engineer 2 (6)
 * </pre>
 */
class PostgresConnectByValidationTest extends PostgresSqlValidationTestBase {

    /**
     * Sets up a realistic employee hierarchy for testing.
     * This data is used across all tests to validate different CONNECT BY scenarios.
     */
    @BeforeEach
    void setupEmployeeHierarchy() throws SQLException {
        executeUpdate("""
            CREATE SCHEMA hr;
            CREATE TABLE hr.employees (
                emp_id INT PRIMARY KEY,
                name TEXT NOT NULL,
                manager_id INT,
                salary NUMERIC,
                dept VARCHAR(50)
            );

            INSERT INTO hr.employees (emp_id, name, manager_id, salary, dept) VALUES
                (1, 'CEO', NULL, 200000, 'Executive'),
                (2, 'VP Sales', 1, 150000, 'Sales'),
                (3, 'VP Engineering', 1, 160000, 'Engineering'),
                (4, 'Sales Manager', 2, 100000, 'Sales'),
                (5, 'Engineer 1', 3, 90000, 'Engineering'),
                (6, 'Engineer 2', 3, 95000, 'Engineering'),
                (7, 'Sales Rep', 4, 60000, 'Sales');
            """);
    }

    /**
     * Comprehensive test: Simple hierarchy with LEVEL, correct traversal order, and parent-child relationships.
     *
     * <p><b>Features tested</b>:
     * <ul>
     *   <li>Basic CONNECT BY PRIOR transformation</li>
     *   <li>START WITH condition (manager_id IS NULL)</li>
     *   <li>LEVEL pseudo-column (should be 1, 2, 3, 4)</li>
     *   <li>Correct row count (all 7 employees)</li>
     *   <li>Hierarchical traversal order</li>
     * </ul>
     *
     * <p><b>Oracle SQL</b>:
     * <pre>
     * SELECT emp_id, name, manager_id, LEVEL as lvl
     * FROM employees
     * START WITH manager_id IS NULL
     * CONNECT BY PRIOR emp_id = manager_id
     * ORDER BY emp_id
     * </pre>
     *
     * <p><b>Expected transformation</b>: Recursive CTE with base case (manager_id IS NULL)
     * and recursive case (JOIN on parent.emp_id = child.manager_id), with level counter.
     */
    @Test
    void simpleHierarchy_correctLevelsAndTraversal() throws SQLException {
        // Given: Oracle CONNECT BY query
        String oracleSql = """
            SELECT emp_id, name, manager_id, LEVEL as lvl
            FROM employees
            START WITH manager_id IS NULL
            CONNECT BY PRIOR emp_id = manager_id
            ORDER BY emp_id
            """;

        // When: Transform to PostgreSQL CTE
        TransformationResult result = transformSql(oracleSql, "hr");

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            () -> "Transformation should succeed. Error: " + result.getErrorMessage());
        assertNotNull(result.getPostgresSql(), "Transformed SQL should not be null");

        // Print transformed SQL for debugging
        if (!result.isSuccess() || result.getErrorMessage() != null) {
            System.out.println("\n=== TRANSFORMATION ERROR ===");
            System.out.println(result.getErrorMessage());
            System.out.println("===========================\n");
        }
        System.out.println("\n=== TRANSFORMED SQL ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================\n");

        // And: Transformed SQL executes successfully
        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        // Verify: Correct number of rows (all 7 employees in hierarchy)
        assertRowCount(7, rows);

        // Verify: Correct LEVEL values for each employee
        // CEO should be level 1
        assertColumnValue(rows, 0, "lvl", 1);  // emp_id=1 (CEO)

        // VPs should be level 2
        assertColumnValue(rows, 1, "lvl", 2);  // emp_id=2 (VP Sales)
        assertColumnValue(rows, 2, "lvl", 2);  // emp_id=3 (VP Engineering)

        // Mid-level managers and engineers should be level 3
        assertColumnValue(rows, 3, "lvl", 3);  // emp_id=4 (Sales Manager)
        assertColumnValue(rows, 4, "lvl", 3);  // emp_id=5 (Engineer 1)
        assertColumnValue(rows, 5, "lvl", 3);  // emp_id=6 (Engineer 2)

        // Sales rep should be level 4 (deepest level)
        assertColumnValue(rows, 6, "lvl", 4);  // emp_id=7 (Sales Rep)

        // Verify: Parent-child relationships preserved
        assertColumnValue(rows, 0, "manager_id", null);  // CEO has no manager
        assertColumnValue(rows, 1, "manager_id", 1);     // VP Sales reports to CEO
        assertColumnValue(rows, 2, "manager_id", 1);     // VP Engineering reports to CEO
        assertColumnValue(rows, 3, "manager_id", 2);     // Sales Manager reports to VP Sales
        assertColumnValue(rows, 6, "manager_id", 4);     // Sales Rep reports to Sales Manager
    }

    /**
     * Comprehensive test: WHERE clause filtering in hierarchical query.
     *
     * <p><b>Features tested</b>:
     * <ul>
     *   <li>WHERE clause correctly filters the hierarchy</li>
     *   <li>Filtering applies to entire result set (not just base case)</li>
     *   <li>LEVEL values still correct after filtering</li>
     *   <li>Multiple conditions in WHERE clause (salary AND dept)</li>
     * </ul>
     *
     * <p><b>Oracle behavior</b>: WHERE clause filters the final result set after hierarchy traversal,
     * so it should exclude rows that don't match the condition while preserving the hierarchy structure
     * for rows that remain.
     *
     * <p><b>Test scenario</b>: Filter to only Engineering department with salary > 90000
     * Should include: CEO (200k, Executive), VP Engineering (160k), Engineer 2 (95k)
     * Should exclude: VP Sales, Sales Manager, Engineer 1 (90k exactly), Sales Rep
     */
    @Test
    void hierarchyWithWhereClause_filtersCorrectly() throws SQLException {
        // Given: CONNECT BY with WHERE clause filtering on salary and department
        String oracleSql = """
            SELECT emp_id, name, salary, dept, LEVEL as lvl
            FROM employees
            WHERE (dept = 'Engineering' AND salary > 90000) OR dept = 'Executive'
            START WITH manager_id IS NULL
            CONNECT BY PRIOR emp_id = manager_id
            ORDER BY emp_id
            """;

        // When: Transform to PostgreSQL CTE
        TransformationResult result = transformSql(oracleSql, "hr");

        // Print transformed SQL for debugging
        if (!result.isSuccess() || result.getErrorMessage() != null) {
            System.out.println("\n=== TRANSFORMATION ERROR ===");
            System.out.println(result.getErrorMessage());
            System.out.println("===========================\n");
        }
        System.out.println("\n=== TRANSFORMED SQL ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================\n");

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            () -> "Transformation should succeed. Error: " + result.getErrorMessage());

        // And: Execute transformed SQL
        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        // Verify: Correct filtering (only 3 employees match)
        assertRowCount(3, rows);

        // Verify: CEO included (dept=Executive)
        assertColumnValue(rows, 0, "name", "CEO");
        assertColumnValue(rows, 0, "lvl", 1);

        // Verify: VP Engineering included (Engineering, 160k > 90k)
        assertColumnValue(rows, 1, "name", "VP Engineering");
        assertColumnValue(rows, 1, "lvl", 2);

        // Verify: Engineer 2 included (Engineering, 95k > 90k)
        assertColumnValue(rows, 2, "name", "Engineer 2");
        assertColumnValue(rows, 2, "lvl", 3);

        // Verify: Sales employees excluded (dept != Engineering)
        rows.forEach(row ->
            assertNotEquals("VP Sales", row.get("name"), "Sales employees should be excluded"));

        // Verify: Engineer 1 excluded (90k not > 90k)
        rows.forEach(row ->
            assertNotEquals("Engineer 1", row.get("name"), "Engineer 1 (salary=90k) should be excluded"));
    }

    /**
     * Comprehensive test: ORDER SIBLINGS BY preserves hierarchy while ordering within levels.
     *
     * <p><b>Features tested</b>:
     * <ul>
     *   <li>ORDER SIBLINGS BY maintains hierarchical structure</li>
     *   <li>Siblings (same parent) ordered by specified column</li>
     *   <li>LEVEL still correct</li>
     *   <li>Parent appears before children</li>
     * </ul>
     *
     * <p><b>Expected order</b> (by name within each level):
     * <pre>
     * Level 1: CEO
     * Level 2: VP Engineering, VP Sales (alphabetical)
     * Level 3: Engineer 1, Engineer 2, Sales Manager (alphabetical)
     * Level 4: Sales Rep
     * </pre>
     */
    @Test
    void orderSiblingsBy_maintainsHierarchyAndSortsWithinLevels() throws SQLException {
        // Given: CONNECT BY with ORDER SIBLINGS BY name
        String oracleSql = """
            SELECT emp_id, name, manager_id, LEVEL as lvl
            FROM employees
            START WITH manager_id IS NULL
            CONNECT BY PRIOR emp_id = manager_id
            ORDER SIBLINGS BY name
            """;

        // When: Transform to PostgreSQL CTE
        TransformationResult result = transformSql(oracleSql, "hr");

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            () -> "Transformation should succeed. Error: " + result.getErrorMessage());

        // And: Execute transformed SQL
        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        // Verify: All rows present
        assertRowCount(7, rows);

        // Verify: Hierarchical structure maintained (parents before children)
        int ceoIdx = findIndexByName(rows, "CEO");
        int vpEngIdx = findIndexByName(rows, "VP Engineering");
        int vpSalesIdx = findIndexByName(rows, "VP Sales");
        int eng1Idx = findIndexByName(rows, "Engineer 1");
        int eng2Idx = findIndexByName(rows, "Engineer 2");

        // CEO (level 1) should appear before VPs (level 2)
        assertTrue(ceoIdx < vpEngIdx, "CEO should appear before VP Engineering");
        assertTrue(ceoIdx < vpSalesIdx, "CEO should appear before VP Sales");

        // VPs (level 2) should appear before their subordinates (level 3)
        assertTrue(vpEngIdx < eng1Idx, "VP Engineering should appear before Engineer 1");
        assertTrue(vpEngIdx < eng2Idx, "VP Engineering should appear before Engineer 2");

        // Verify: Within same level, sorted alphabetically by name
        // Level 2: "VP Engineering" before "VP Sales"
        assertTrue(vpEngIdx < vpSalesIdx,
            "Within level 2, 'VP Engineering' should come before 'VP Sales' (alphabetical)");

        // Level 3: "Engineer 1" before "Engineer 2" before "Sales Manager"
        int salesMgrIdx = findIndexByName(rows, "Sales Manager");
        assertTrue(eng1Idx < eng2Idx,
            "Within level 3, 'Engineer 1' should come before 'Engineer 2' (alphabetical)");
        assertTrue(eng2Idx < salesMgrIdx,
            "Within level 3, 'Engineer 2' should come before 'Sales Manager' (alphabetical)");
    }

    /**
     * Comprehensive test: Multiple START WITH conditions and complex CONNECT BY.
     *
     * <p><b>Features tested</b>:
     * <ul>
     *   <li>START WITH with OR condition (multiple roots)</li>
     *   <li>Multiple hierarchy trees in same result</li>
     *   <li>LEVEL starts at 1 for each root</li>
     *   <li>Each tree traverses independently</li>
     * </ul>
     *
     * <p><b>Test scenario</b>: Start from both VP positions (creating two separate trees)
     * Tree 1: VP Sales → Sales Manager → Sales Rep
     * Tree 2: VP Engineering → Engineer 1, Engineer 2
     */
    @Test
    void multipleStartWith_createsMultipleTrees() throws SQLException {
        // Given: CONNECT BY starting from both VPs (creating forest of two trees)
        String oracleSql = """
            SELECT emp_id, name, manager_id, LEVEL as lvl
            FROM employees
            START WITH emp_id IN (2, 3)
            CONNECT BY PRIOR emp_id = manager_id
            ORDER BY emp_id
            """;

        // When: Transform to PostgreSQL CTE
        TransformationResult result = transformSql(oracleSql, "hr");

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            () -> "Transformation should succeed. Error: " + result.getErrorMessage());

        // And: Execute transformed SQL
        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        // Verify: 5 employees (2 roots + 3 subordinates, CEO excluded)
        assertRowCount(5, rows);

        // Verify: Both VPs are at level 1 (roots of their respective trees)
        int vpSalesIdx = findIndexByName(rows, "VP Sales");
        int vpEngIdx = findIndexByName(rows, "VP Engineering");
        assertColumnValue(rows, vpSalesIdx, "lvl", 1);
        assertColumnValue(rows, vpEngIdx, "lvl", 1);

        // Verify: Their direct reports are at level 2
        int salesMgrIdx = findIndexByName(rows, "Sales Manager");
        int eng1Idx = findIndexByName(rows, "Engineer 1");
        int eng2Idx = findIndexByName(rows, "Engineer 2");
        assertColumnValue(rows, salesMgrIdx, "lvl", 2);
        assertColumnValue(rows, eng1Idx, "lvl", 2);
        assertColumnValue(rows, eng2Idx, "lvl", 2);

        // Verify: CEO not included (not a root and not under VPs)
        rows.forEach(row ->
            assertNotEquals("CEO", row.get("name"), "CEO should not be in result (not a root)"));
    }

    /**
     * Comprehensive test: PRIOR on right side of comparison (reversed direction).
     *
     * <p><b>Features tested</b>:
     * <ul>
     *   <li>CONNECT BY with PRIOR on right side: emp_id = PRIOR manager_id</li>
     *   <li>Traversal direction reversed (bottom-up instead of top-down)</li>
     *   <li>Starting from leaf nodes and going up to root</li>
     *   <li>LEVEL still increments correctly</li>
     * </ul>
     *
     * <p><b>Test scenario</b>: Start from Sales Rep and traverse upward to CEO
     * Expected path: Sales Rep (lvl 1) → Sales Manager (lvl 2) → VP Sales (lvl 3) → CEO (lvl 4)
     */
    @Test
    void connectByPriorReversed_traversesBottomUp() throws SQLException {
        // Given: Bottom-up traversal starting from Sales Rep
        String oracleSql = """
            SELECT emp_id, name, manager_id, LEVEL as lvl
            FROM employees
            START WITH emp_id = 7
            CONNECT BY emp_id = PRIOR manager_id
            ORDER BY lvl
            """;

        // When: Transform to PostgreSQL CTE
        TransformationResult result = transformSql(oracleSql, "hr");

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            () -> "Transformation should succeed. Error: " + result.getErrorMessage());

        // And: Execute transformed SQL
        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        // Verify: 4 employees in upward chain (Sales Rep → Manager → VP → CEO)
        assertRowCount(4, rows);

        // Verify: Correct upward traversal order and levels
        assertColumnValue(rows, 0, "name", "Sales Rep");
        assertColumnValue(rows, 0, "lvl", 1);

        assertColumnValue(rows, 1, "name", "Sales Manager");
        assertColumnValue(rows, 1, "lvl", 2);

        assertColumnValue(rows, 2, "name", "VP Sales");
        assertColumnValue(rows, 2, "lvl", 3);

        assertColumnValue(rows, 3, "name", "CEO");
        assertColumnValue(rows, 3, "lvl", 4);

        // Verify: Engineering branch not included (different subtree)
        rows.forEach(row -> {
            String name = (String) row.get("name");
            assertFalse(name.contains("Engineer"),
                "Engineering employees should not be in Sales Rep's upward path");
        });
    }

    // ========== Helper Methods ==========

    /**
     * Finds the index of a row by employee name.
     *
     * @param rows the query results
     * @param name the employee name to find
     * @return the 0-based index, or -1 if not found
     */
    private int findIndexByName(List<Map<String, Object>> rows, String name) {
        for (int i = 0; i < rows.size(); i++) {
            if (name.equals(rows.get(i).get("name"))) {
                return i;
            }
        }
        return -1;
    }
}
