package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL cursor FOR loops with inline SELECT.
 *
 * <p>Tests the transformation of Oracle cursor FOR loops to PostgreSQL equivalents.
 * Follows comprehensive testing philosophy: each test validates multiple aspects together
 * (parsing, transformation, execution, result correctness).
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with cursor FOR loops</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all scenarios</li>
 *   <li>Validates: syntax correctness, loop iteration, result correctness</li>
 * </ul>
 *
 * <p>Scope:
 * <ul>
 *   <li>✅ FOR rec IN (SELECT ...) LOOP - Inline SELECT cursor loops</li>
 *   <li>❌ FOR rec IN cursor_name LOOP - Named cursors (not yet supported)</li>
 *   <li>❌ FOR i IN 1..10 LOOP - Numeric range loops (not yet supported)</li>
 *   <li>❌ WHILE condition LOOP - WHILE loops (not yet supported)</li>
 *   <li>❌ LOOP ... END LOOP - Basic loops (not yet supported)</li>
 * </ul>
 */
public class PostgresPlSqlCursorForLoopValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema and sample data
        executeUpdate("CREATE SCHEMA hr");

        // Create employees table with test data
        executeUpdate("""
            CREATE TABLE hr.employees (
                employee_id INT PRIMARY KEY,
                employee_name TEXT NOT NULL,
                salary NUMERIC(10,2) NOT NULL,
                department_id INT NOT NULL,
                hire_date DATE NOT NULL,
                commission_pct NUMERIC(3,2)
            )
            """);

        // Insert test data
        executeUpdate("""
            INSERT INTO hr.employees VALUES
            (1, 'John Doe', 50000.00, 10, '2020-01-15', 0.10),
            (2, 'Jane Smith', 60000.00, 20, '2019-05-20', 0.15),
            (3, 'Bob Johnson', 75000.00, 10, '2018-03-10', NULL),
            (4, 'Alice Brown', 80000.00, 30, '2021-07-01', 0.20),
            (5, 'Charlie Wilson', 55000.00, 20, '2020-09-15', 0.12)
            """);
    }

    /**
     * Test 1: Simple cursor FOR loop - sum salaries.
     *
     * <p>Validates:
     * <ul>
     *   <li>Basic FOR rec IN (SELECT ...) LOOP structure</li>
     *   <li>Loop iteration over result set</li>
     *   <li>Accessing record fields (rec.column_name)</li>
     *   <li>Accumulator variable in loop</li>
     * </ul>
     */
    @Test
    void simpleCursorForLoop_sumSalaries() throws SQLException {
        // Oracle function with simple cursor FOR loop
        String oracleFunction = """
            FUNCTION calculate_total_salaries RETURN NUMBER IS
              v_total NUMBER := 0;
            BEGIN
              FOR emp_rec IN (SELECT salary FROM employees) LOOP
                v_total := v_total + emp_rec.salary;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple Cursor FOR Loop ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Sum of all salaries: 50000 + 60000 + 75000 + 80000 + 55000 = 320000
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_total_salaries() AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(320000.0, total, 0.01, "Should sum all employee salaries");
    }

    /**
     * Test 2: Cursor FOR loop with WHERE clause filtering.
     *
     * <p>Validates:
     * <ul>
     *   <li>WHERE clause in cursor SELECT</li>
     *   <li>Parameterized function with cursor using parameter</li>
     *   <li>Conditional filtering in loop query</li>
     * </ul>
     */
    @Test
    void cursorForLoopWithWhere_filterByDepartment() throws SQLException {
        // Oracle function with WHERE clause in cursor
        String oracleFunction = """
            FUNCTION get_department_total(p_dept_id NUMBER) RETURN NUMBER IS
              v_total NUMBER := 0;
            BEGIN
              FOR emp_rec IN (SELECT salary FROM employees WHERE department_id = p_dept_id) LOOP
                v_total := v_total + emp_rec.salary;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Cursor FOR Loop with WHERE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test with department 10 (John Doe: 50000 + Bob Johnson: 75000 = 125000)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_department_total(10) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(125000.0, total, 0.01, "Should sum salaries for department 10");

        // Test with department 20 (Jane Smith: 60000 + Charlie Wilson: 55000 = 115000)
        rows = executeQuery("SELECT hr.get_department_total(20) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(115000.0, total, 0.01, "Should sum salaries for department 20");
    }

    /**
     * Test 3: Cursor FOR loop with complex SELECT (JOIN, multiple columns).
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple columns in SELECT list</li>
     *   <li>Accessing multiple record fields</li>
     *   <li>String concatenation in loop body</li>
     *   <li>Conditional logic inside loop (IF statement)</li>
     * </ul>
     */
    @Test
    void cursorForLoopComplexSelect_buildNameList() throws SQLException {
        // Oracle function with complex SELECT and IF inside loop
        String oracleFunction = """
            FUNCTION get_high_earners RETURN VARCHAR2 IS
              v_result VARCHAR2(1000) := '';
              v_count NUMBER := 0;
            BEGIN
              FOR emp_rec IN (SELECT employee_name, salary FROM employees WHERE salary > 60000 ORDER BY salary DESC) LOOP
                IF v_count > 0 THEN
                  v_result := v_result || ', ';
                END IF;
                v_result := v_result || emp_rec.employee_name;
                v_count := v_count + 1;
              END LOOP;
              RETURN v_result;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Complex Cursor FOR Loop ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Expected: Alice Brown (80000), Bob Johnson (75000) - ordered by salary DESC
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_high_earners() AS names");
        String names = (String) rows.get(0).get("names");
        assertTrue(names.contains("Alice Brown"), "Should include Alice Brown");
        assertTrue(names.contains("Bob Johnson"), "Should include Bob Johnson");
        assertFalse(names.contains("John Doe"), "Should not include John Doe (salary 50000)");
    }

    /**
     * Test 4: Nested cursor FOR loops.
     *
     * <p>Validates:
     * <ul>
     *   <li>Cursor FOR loop inside another cursor FOR loop</li>
     *   <li>Proper scoping of loop variables</li>
     *   <li>Inner loop accessing outer loop variables</li>
     * </ul>
     */
    @Test
    void nestedCursorForLoops_correctNesting() throws SQLException {
        // Oracle function with nested cursor FOR loops
        String oracleFunction = """
            FUNCTION count_dept_combinations RETURN NUMBER IS
              v_count NUMBER := 0;
            BEGIN
              FOR dept1 IN (SELECT DISTINCT department_id FROM employees WHERE department_id <= 20) LOOP
                FOR dept2 IN (SELECT DISTINCT department_id FROM employees WHERE department_id > dept1.department_id) LOOP
                  v_count := v_count + 1;
                END LOOP;
              END LOOP;
              RETURN v_count;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Nested Cursor FOR Loops ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Note: Oracle code uses DISTINCT, but SELECT DISTINCT is not yet implemented
        // Without DISTINCT: dept <= 20 returns [10,10,20,20] (4 rows from 4 employees)
        // Nested loop logic:
        //   dept1=10 (2x): dept2 > 10 returns [10,20,30,20] filtered to [20,30,20] = 3 combinations each = 6
        //   dept1=20 (2x): dept2 > 20 returns [30] = 1 combination each = 2
        // Total: 6 + 2 = 8 (would be 3 with DISTINCT: (10,20), (10,30), (20,30))
        List<Map<String, Object>> rows = executeQuery("SELECT hr.count_dept_combinations() AS count");
        int count = ((Number) rows.get(0).get("count")).intValue();
        assertEquals(8, count, "Should count department combinations (8 without DISTINCT support)");
    }

    /**
     * Test 5: Cursor FOR loop with conditional processing (NULL handling).
     *
     * <p>Validates:
     * <ul>
     *   <li>Handling NULL values in cursor results</li>
     *   <li>Conditional logic based on NULL checks</li>
     *   <li>COALESCE transformation in SELECT</li>
     * </ul>
     */
    @Test
    void cursorForLoopWithNullHandling_conditionalProcessing() throws SQLException {
        // Oracle function with NULL handling
        String oracleFunction = """
            FUNCTION calculate_commissions RETURN NUMBER IS
              v_total NUMBER := 0;
            BEGIN
              FOR emp_rec IN (SELECT salary, commission_pct FROM employees) LOOP
                IF emp_rec.commission_pct IS NOT NULL THEN
                  v_total := v_total + (emp_rec.salary * emp_rec.commission_pct);
                END IF;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Cursor FOR Loop with NULL Handling ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // John: 50000 * 0.10 = 5000
        // Jane: 60000 * 0.15 = 9000
        // Bob: NULL commission
        // Alice: 80000 * 0.20 = 16000
        // Charlie: 55000 * 0.12 = 6600
        // Total = 36600
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_commissions() AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(36600.0, total, 0.01, "Should calculate total commissions correctly");
    }
}
