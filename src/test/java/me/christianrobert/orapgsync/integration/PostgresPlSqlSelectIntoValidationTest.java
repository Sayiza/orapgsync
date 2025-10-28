package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL SELECT INTO statements.
 *
 * <p>Tests the transformation of Oracle SELECT INTO statements to PostgreSQL equivalents.
 * Follows comprehensive testing philosophy: each test validates multiple aspects together
 * (parsing, transformation, execution, result correctness).
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with SELECT INTO statements</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all scenarios</li>
 *   <li>Validates: syntax correctness, query execution, variable assignment, result correctness</li>
 * </ul>
 */
public class PostgresPlSqlSelectIntoValidationTest extends PostgresSqlValidationTestBase {

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
                hire_date DATE NOT NULL
            )
            """);

        // Insert test data
        executeUpdate("""
            INSERT INTO hr.employees VALUES
            (1, 'John Doe', 50000.00, 10, '2020-01-15'),
            (2, 'Jane Smith', 60000.00, 20, '2019-05-20'),
            (3, 'Bob Johnson', 75000.00, 10, '2018-03-10'),
            (4, 'Alice Brown', 80000.00, 30, '2021-07-01')
            """);
    }

    /**
     * Test 1: Simple SELECT INTO with single variable.
     *
     * <p>Validates:
     * <ul>
     *   <li>Basic SELECT INTO structure</li>
     *   <li>Single column selection</li>
     *   <li>Single variable assignment</li>
     *   <li>WHERE clause condition</li>
     * </ul>
     */
    @Test
    void simpleSelectInto_singleVariable() throws SQLException {
        // Oracle function with simple SELECT INTO
        String oracleFunction = """
            FUNCTION get_employee_name(p_emp_id NUMBER) RETURN VARCHAR2 IS
              v_name VARCHAR2(100);
            BEGIN
              SELECT employee_name INTO v_name
              FROM employees WHERE employee_id = p_emp_id;
              RETURN v_name;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple SELECT INTO ===" );
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_employee_name(1) AS name");
        assertEquals("John Doe", rows.get(0).get("name"), "Should return employee name");

        // Test with different employee
        rows = executeQuery("SELECT hr.get_employee_name(2) AS name");
        assertEquals("Jane Smith", rows.get(0).get("name"), "Should return different employee name");
    }

    /**
     * Test 2: SELECT INTO with multiple variables.
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple column selection</li>
     *   <li>Multiple variable assignments</li>
     *   <li>Variable list transformation</li>
     *   <li>Returning composite result</li>
     * </ul>
     */
    @Test
    void selectIntoMultipleVariables_correctAssignment() throws SQLException {
        // Oracle function with SELECT INTO multiple variables
        String oracleFunction = """
            FUNCTION get_employee_info(p_emp_id NUMBER) RETURN VARCHAR2 IS
              v_name VARCHAR2(100);
              v_salary NUMBER;
              v_dept NUMBER;
            BEGIN
              SELECT employee_name, salary, department_id INTO v_name, v_salary, v_dept
              FROM employees WHERE employee_id = p_emp_id;
              RETURN v_name || ' - Salary: ' || v_salary || ' - Dept: ' || v_dept;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Multiple Variable SELECT INTO ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_employee_info(1) AS info");
        String info = (String) rows.get(0).get("info");
        assertTrue(info.contains("John Doe"), "Result should contain employee name");
        assertTrue(info.contains("50000"), "Result should contain salary");
        assertTrue(info.contains("Dept: 10"), "Result should contain department");
    }

    /**
     * Test 3: SELECT INTO with aggregate functions.
     *
     * <p>Validates:
     * <ul>
     *   <li>Aggregate function transformation (SUM, AVG, COUNT)</li>
     *   <li>GROUP BY compatibility</li>
     *   <li>Numeric result handling</li>
     * </ul>
     */
    @Test
    void selectIntoWithAggregates_correctCalculation() throws SQLException {
        // Oracle function with aggregate SELECT INTO
        String oracleFunction = """
            FUNCTION get_department_stats(p_dept_id NUMBER) RETURN NUMBER IS
              v_avg_salary NUMBER;
              v_emp_count NUMBER;
            BEGIN
              SELECT AVG(salary), COUNT(*) INTO v_avg_salary, v_emp_count
              FROM employees WHERE department_id = p_dept_id;
              RETURN v_avg_salary * v_emp_count;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed SELECT INTO with Aggregates ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution - Department 10 has 2 employees: 50000 and 75000
        // AVG = 62500, COUNT = 2, result = 125000
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_department_stats(10) AS stats");
        double stats = ((Number) rows.get(0).get("stats")).doubleValue();
        assertEquals(125000.0, stats, 0.01, "Should calculate correct department stats");

        // Test with single employee department
        rows = executeQuery("SELECT hr.get_department_stats(20) AS stats");
        stats = ((Number) rows.get(0).get("stats")).doubleValue();
        assertEquals(60000.0, stats, 0.01, "Should handle single employee department");
    }

    /**
     * Test 4: SELECT INTO with complex WHERE clause.
     *
     * <p>Validates:
     * <ul>
     *   <li>Complex WHERE conditions (AND/OR)</li>
     *   <li>Date comparison in WHERE clause</li>
     *   <li>Multiple predicates</li>
     * </ul>
     */
    @Test
    void selectIntoComplexWhere_correctFiltering() throws SQLException {
        // Oracle function with complex WHERE clause
        String oracleFunction = """
            FUNCTION get_senior_employee_salary(p_dept_id NUMBER) RETURN NUMBER IS
              v_max_salary NUMBER;
            BEGIN
              SELECT MAX(salary) INTO v_max_salary
              FROM employees
              WHERE department_id = p_dept_id
                AND hire_date < DATE '2020-01-01';
              RETURN v_max_salary;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed SELECT INTO with Complex WHERE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Department 10: Bob Johnson hired 2018-03-10 with salary 75000
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_senior_employee_salary(10) AS max_sal");
        double maxSal = ((Number) rows.get(0).get("max_sal")).doubleValue();
        assertEquals(75000.0, maxSal, 0.01, "Should return highest salary of senior employees");
    }

    /**
     * Test 5: SELECT INTO with calculation and type conversion.
     *
     * <p>Validates:
     * <ul>
     *   <li>Expression in SELECT list</li>
     *   <li>Arithmetic operations</li>
     *   <li>Type conversion</li>
     *   <li>ROUND function transformation</li>
     * </ul>
     */
    @Test
    void selectIntoWithCalculation_correctResult() throws SQLException {
        // Oracle function with calculation in SELECT INTO
        String oracleFunction = """
            FUNCTION calculate_bonus(p_emp_id NUMBER) RETURN NUMBER IS
              v_bonus NUMBER;
            BEGIN
              SELECT ROUND(salary * 0.10, 2) INTO v_bonus
              FROM employees WHERE employee_id = p_emp_id;
              RETURN v_bonus;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed SELECT INTO with Calculation ===");
        System.out.println(result.getPostgresSql());
        System.out.println("================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_bonus(1) AS bonus");
        double bonus = ((Number) rows.get(0).get("bonus")).doubleValue();
        assertEquals(5000.0, bonus, 0.01, "Should calculate 10% bonus correctly");

        // Test with different employee
        rows = executeQuery("SELECT hr.calculate_bonus(3) AS bonus");
        bonus = ((Number) rows.get(0).get("bonus")).doubleValue();
        assertEquals(7500.0, bonus, 0.01, "Should calculate bonus for different employee");
    }
}
