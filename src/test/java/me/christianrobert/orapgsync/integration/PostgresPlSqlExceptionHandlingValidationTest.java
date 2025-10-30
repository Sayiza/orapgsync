package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL exception handling.
 *
 * <p><strong>Phase 1: Basic Exception Handling</strong>
 * <ul>
 *   <li>EXCEPTION block structure</li>
 *   <li>WHEN OTHERS handler</li>
 *   <li>Standard exception name mapping (20+ exceptions)</li>
 *   <li>Multiple exception handlers</li>
 *   <li>Multiple exceptions in one handler (OR)</li>
 *   <li>RAISE statement (re-raise and named exceptions)</li>
 *   <li>SQLERRM function (no-arg)</li>
 * </ul>
 *
 * <p>Tests verify Oracle PL/SQL → PostgreSQL PL/pgSQL transformation produces
 * valid, executable PostgreSQL code with correct exception handling semantics.
 */
public class PostgresPlSqlExceptionHandlingValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema and sample table
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("""
            CREATE TABLE hr.employees (
                emp_id INT PRIMARY KEY,
                employee_name TEXT,
                salary NUMERIC
            )
            """);
        executeUpdate("INSERT INTO hr.employees VALUES (1, 'Alice', 50000), (2, 'Bob', 60000)");
    }

    /**
     * Test 1: Basic WHEN OTHERS exception handler.
     *
     * <p>WHEN OTHERS catches all exceptions not explicitly handled.
     * Syntax is identical in Oracle and PostgreSQL.
     */
    @Test
    void exceptionHandler_whenOthers() throws SQLException {
        String oracleFunction = """
            FUNCTION safe_divide(p_dividend NUMBER, p_divisor NUMBER) RETURN NUMBER IS
              v_result NUMBER;
            BEGIN
              v_result := p_dividend / p_divisor;
              RETURN v_result;
            EXCEPTION
              WHEN OTHERS THEN
                RETURN NULL;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed WHEN OTHERS Handler ===");
        System.out.println(result.getPostgresSql());
        System.out.println("========================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify structure
        String transformed = result.getPostgresSql();
        assertAll(
                () -> assertTrue(transformed.contains("EXCEPTION"), "Should have EXCEPTION block"),
                () -> assertTrue(transformed.contains("WHEN OTHERS THEN"), "Should have WHEN OTHERS handler"),
                () -> assertTrue(transformed.contains("RETURN NULL"), "Should preserve return statement")
        );

        // Create and test function
        executeUpdate(result.getPostgresSql());

        // Test normal case
        List<Map<String, Object>> normalResult = executeQuery("SELECT hr.safe_divide(10, 2) as result");
        assertEquals(1, normalResult.size());
        assertEquals(5.0, ((Number) normalResult.get(0).get("result")).doubleValue(), 0.001);

        // Test division by zero (caught by WHEN OTHERS)
        List<Map<String, Object>> errorResult = executeQuery("SELECT hr.safe_divide(10, 0) as result");
        assertEquals(1, errorResult.size());
        assertNull(errorResult.get(0).get("result"), "Should return NULL on error");
    }

    /**
     * Test 2: Specific exception handler for NO_DATA_FOUND.
     *
     * <p>Verifies exception name mapping: NO_DATA_FOUND → no_data_found.
     */
    @Test
    void exceptionHandler_noDataFound() throws SQLException {
        String oracleFunction = """
            FUNCTION get_employee_name(p_emp_id NUMBER) RETURN VARCHAR2 IS
              v_name VARCHAR2(100);
            BEGIN
              SELECT employee_name INTO v_name FROM hr.employees WHERE emp_id = p_emp_id;
              RETURN v_name;
            EXCEPTION
              WHEN NO_DATA_FOUND THEN
                RETURN 'Employee not found';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed NO_DATA_FOUND Handler ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify exception name mapping
        String transformed = result.getPostgresSql();
        assertAll(
                () -> assertFalse(transformed.contains("NO_DATA_FOUND"), "Should not have uppercase exception name"),
                () -> assertTrue(transformed.contains("WHEN no_data_found THEN"), "Should have lowercase exception name")
        );

        // Create and test function
        executeUpdate(result.getPostgresSql());

        // Test with non-existent employee (triggers NO_DATA_FOUND)
        List<Map<String, Object>> queryResult = executeQuery("SELECT hr.get_employee_name(999999) as result");
        assertEquals(1, queryResult.size());
        assertEquals("Employee not found", queryResult.get(0).get("result"));
    }

    /**
     * Test 3: Multiple exception handlers.
     *
     * <p>Verifies multiple WHEN clauses are correctly transformed.
     */
    @Test
    void exceptionHandler_multipleHandlers() throws SQLException {
        String oracleFunction = """
            FUNCTION validate_data(p_value NUMBER) RETURN VARCHAR2 IS
              v_result VARCHAR2(100);
              v_test NUMBER;
            BEGIN
              SELECT emp_id INTO v_test FROM hr.employees WHERE emp_id = p_value;
              v_result := 'Found';
              RETURN v_result;
            EXCEPTION
              WHEN NO_DATA_FOUND THEN
                RETURN 'Not found';
              WHEN TOO_MANY_ROWS THEN
                RETURN 'Multiple found';
              WHEN OTHERS THEN
                RETURN 'Error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Multiple Handlers ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify all handlers present
        String transformed = result.getPostgresSql();
        assertAll(
                () -> assertTrue(transformed.contains("WHEN no_data_found THEN"), "Should have no_data_found handler"),
                () -> assertTrue(transformed.contains("WHEN too_many_rows THEN"), "Should have too_many_rows handler"),
                () -> assertTrue(transformed.contains("WHEN OTHERS THEN"), "Should have OTHERS handler")
        );

        executeUpdate(result.getPostgresSql());

        // Test NO_DATA_FOUND path
        List<Map<String, Object>> notFoundResult = executeQuery("SELECT hr.validate_data(999999) as result");
        assertEquals("Not found", notFoundResult.get(0).get("result"));

        // Test Found path
        List<Map<String, Object>> foundResult = executeQuery("SELECT hr.validate_data(1) as result");
        assertEquals("Found", foundResult.get(0).get("result"));
    }

    /**
     * Test 4: Multiple exceptions in one handler using OR.
     *
     * <p>Oracle: WHEN e1 OR e2 THEN
     * <p>PostgreSQL: WHEN e1 OR e2 THEN (syntax identical)
     */
    @Test
    void exceptionHandler_multipleExceptionsWithOr() throws SQLException {
        String oracleFunction = """
            FUNCTION process_value(p_value NUMBER) RETURN VARCHAR2 IS
              v_result NUMBER;
            BEGIN
              v_result := p_value / 0;
              RETURN 'Success';
            EXCEPTION
              WHEN ZERO_DIVIDE OR VALUE_ERROR THEN
                RETURN 'Math error';
              WHEN OTHERS THEN
                RETURN 'Other error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Multiple Exceptions with OR ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify OR clause with mapped exception names
        String transformed = result.getPostgresSql();
        assertTrue(transformed.contains("WHEN division_by_zero OR invalid_text_representation THEN"),
                "Should map both exceptions with OR");

        executeUpdate(result.getPostgresSql());

        // Test division by zero (should be caught by first handler)
        List<Map<String, Object>> queryResult = executeQuery("SELECT hr.process_value(10) as result");
        assertEquals("Math error", queryResult.get(0).get("result"));
    }

    /**
     * Test 5: Exception name mapping for standard Oracle exceptions.
     *
     * <p>Verifies that exceptions with name changes are correctly mapped:
     * <ul>
     *   <li>ZERO_DIVIDE → division_by_zero</li>
     *   <li>DUP_VAL_ON_INDEX → unique_violation</li>
     * </ul>
     */
    @Test
    void exceptionHandler_standardExceptionMapping() throws SQLException {
        String oracleFunction = """
            FUNCTION test_mapping RETURN VARCHAR2 IS
            BEGIN
              RETURN 'OK';
            EXCEPTION
              WHEN ZERO_DIVIDE THEN
                RETURN 'Division error';
              WHEN DUP_VAL_ON_INDEX THEN
                RETURN 'Duplicate key';
              WHEN INVALID_CURSOR THEN
                RETURN 'Cursor error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Exception Name Mapping ===");
        System.out.println(result.getPostgresSql());
        if (!result.isSuccess()) {
            System.out.println("ERROR: " + result.getErrorMessage());
        }
        System.out.println("==========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify exception name transformations
        String transformed = result.getPostgresSql();
        assertAll(
                () -> assertTrue(transformed.contains("WHEN division_by_zero THEN"),
                        "ZERO_DIVIDE should map to division_by_zero"),
                () -> assertTrue(transformed.contains("WHEN unique_violation THEN"),
                        "DUP_VAL_ON_INDEX should map to unique_violation"),
                () -> assertTrue(transformed.contains("WHEN invalid_cursor_state THEN"),
                        "INVALID_CURSOR should map to invalid_cursor_state")
        );

        executeUpdate(result.getPostgresSql());

        // Test normal execution
        List<Map<String, Object>> queryResult = executeQuery("SELECT hr.test_mapping() as result");
        assertEquals("OK", queryResult.get(0).get("result"));
    }

    /**
     * Test 6: RAISE statement with no exception name (re-raise).
     *
     * <p>RAISE; re-raises the current exception. Syntax is identical in Oracle and PostgreSQL.
     */
    @Test
    void raiseStatement_reRaise() throws SQLException {
        String oracleFunction = """
            FUNCTION log_and_reraise(p_value NUMBER) RETURN NUMBER IS
              v_result NUMBER;
              v_log VARCHAR2(100);
            BEGIN
              v_result := 1 / p_value;
              RETURN v_result;
            EXCEPTION
              WHEN OTHERS THEN
                v_log := 'Error logged';
                RAISE;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed RAISE (Re-raise) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify RAISE statement
        String transformed = result.getPostgresSql();
        assertTrue(transformed.contains("RAISE"), "Should have RAISE statement");

        executeUpdate(result.getPostgresSql());

        // Test that exception is re-raised (should throw exception)
        SQLException exception = assertThrows(SQLException.class, () -> {
            executeQuery("SELECT hr.log_and_reraise(0) as result");
        });
        assertTrue(exception.getMessage().contains("division by zero") ||
                   exception.getMessage().contains("zero"),
                "Should re-raise division by zero exception");
    }

    /**
     * Test 7: RAISE statement with exception name.
     *
     * <p>Phase 1: Only supports standard exceptions.
     * <p>Example: RAISE ZERO_DIVIDE; → RAISE division_by_zero;
     */
    @Test
    void raiseStatement_namedStandardException() throws SQLException {
        String oracleFunction = """
            FUNCTION check_threshold(p_value NUMBER) RETURN VARCHAR2 IS
            BEGIN
              IF p_value < 0 THEN
                RAISE ZERO_DIVIDE;
              END IF;
              RETURN 'OK';
            EXCEPTION
              WHEN ZERO_DIVIDE THEN
                RETURN 'Invalid value';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed RAISE Named Exception ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify RAISE with exception name
        String transformed = result.getPostgresSql();
        assertTrue(transformed.contains("RAISE division_by_zero"),
                "Should have lowercase exception name in RAISE");

        executeUpdate(result.getPostgresSql());

        // Test exception raising
        List<Map<String, Object>> errorResult = executeQuery("SELECT hr.check_threshold(-5) as result");
        assertEquals("Invalid value", errorResult.get(0).get("result"));

        // Test normal case
        List<Map<String, Object>> okResult = executeQuery("SELECT hr.check_threshold(5) as result");
        assertEquals("OK", okResult.get(0).get("result"));
    }

    /**
     * Test 8: SQLERRM function in exception handler.
     *
     * <p>SQLERRM (no args) returns current error message.
     * <p>Syntax is identical in Oracle and PostgreSQL.
     */
    @Test
    void exceptionHandler_sqlerrm() throws SQLException {
        String oracleFunction = """
            FUNCTION get_error_message(p_value NUMBER) RETURN VARCHAR2 IS
              v_result NUMBER;
            BEGIN
              v_result := 1 / p_value;
              RETURN 'Success';
            EXCEPTION
              WHEN OTHERS THEN
                RETURN 'Error: ' || SQLERRM;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed SQLERRM Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Verify SQLERRM preserved
        String transformed = result.getPostgresSql();
        assertTrue(transformed.contains("SQLERRM"), "Should preserve SQLERRM function");

        executeUpdate(result.getPostgresSql());

        // Test error path (returns error message)
        List<Map<String, Object>> queryResult = executeQuery("SELECT hr.get_error_message(0) as result");
        String errorMsg = (String) queryResult.get(0).get("result");
        assertTrue(errorMsg.startsWith("Error:"), "Should return error message");
        assertTrue(errorMsg.toLowerCase().contains("division") || errorMsg.toLowerCase().contains("zero"),
                "Error message should mention division by zero");
    }

    /**
     * Test 9: Nested BEGIN...EXCEPTION...END blocks.
     *
     * <p>Verifies exception handlers work correctly in nested blocks.
     */
    @Test
    void exceptionHandler_nestedBlocks() throws SQLException {
        String oracleFunction = """
            FUNCTION nested_exception_test(p_outer NUMBER, p_inner NUMBER) RETURN VARCHAR2 IS
              v_result VARCHAR2(100);
            BEGIN
              v_result := 'Outer start';

              BEGIN
                v_result := v_result || ' Inner: ' || (p_inner / 0);
              EXCEPTION
                WHEN OTHERS THEN
                  v_result := v_result || ' Inner caught';
              END;

              v_result := v_result || ' Outer continues';

              IF p_outer = 0 THEN
                v_result := 1 / p_outer;
              END IF;

              RETURN v_result;
            EXCEPTION
              WHEN OTHERS THEN
                RETURN v_result || ' Outer caught';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Nested Exception Blocks ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        executeUpdate(result.getPostgresSql());

        // Test inner exception caught
        List<Map<String, Object>> innerResult = executeQuery("SELECT hr.nested_exception_test(5, 0) as result");
        assertEquals("Outer start Inner caught Outer continues", innerResult.get(0).get("result"),
                "Should catch inner exception and continue");

        // Test outer exception caught
        List<Map<String, Object>> outerResult = executeQuery("SELECT hr.nested_exception_test(0, 5) as result");
        String outerResultStr = (String) outerResult.get(0).get("result");
        assertTrue(outerResultStr.contains("Outer caught"), "Should catch outer exception");
    }

    /**
     * Test 10: Exception handler with SELECT INTO that raises NO_DATA_FOUND.
     *
     * <p>Comprehensive test verifying exception flow with database operations.
     */
    @Test
    void exceptionHandler_selectIntoNoDataFound() throws SQLException {
        String oracleFunction = """
            FUNCTION find_employee_salary(p_emp_id NUMBER) RETURN NUMBER IS
              v_salary NUMBER;
            BEGIN
              SELECT salary INTO v_salary FROM hr.employees WHERE emp_id = p_emp_id;
              RETURN v_salary;
            EXCEPTION
              WHEN NO_DATA_FOUND THEN
                RETURN -1;
              WHEN TOO_MANY_ROWS THEN
                RETURN -2;
              WHEN OTHERS THEN
                RETURN -999;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed SELECT INTO with Exception Handling ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        executeUpdate(result.getPostgresSql());

        // Test NO_DATA_FOUND case
        List<Map<String, Object>> notFoundResult = executeQuery("SELECT hr.find_employee_salary(999999) as result");
        assertEquals(-1, ((Number) notFoundResult.get(0).get("result")).intValue());

        // Test found case
        List<Map<String, Object>> foundResult = executeQuery("SELECT hr.find_employee_salary(1) as result");
        assertEquals(50000, ((Number) foundResult.get(0).get("result")).intValue());
    }
}
