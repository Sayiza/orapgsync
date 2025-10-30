package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PL/SQL Exception Handling Phase 2: RAISE_APPLICATION_ERROR.
 * <p>
 * Phase 2 features:
 * - RAISE_APPLICATION_ERROR → RAISE EXCEPTION with ERRCODE mapping
 * <p>
 * Test strategy:
 * - Transform Oracle functions with RAISE_APPLICATION_ERROR
 * - Install in PostgreSQL
 * - Execute and verify behavior matches Oracle semantics
 */
public class PostgresPlSqlExceptionHandlingPhase2ValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Tests RAISE_APPLICATION_ERROR with literal error code.
     * <p>
     * Verifies:
     * - Error code -20001 maps to SQLSTATE 'P0001'
     * - Custom error message is preserved
     * - Original Oracle error code preserved in HINT
     */
    @Test
    void raiseApplicationError_literalCode() throws SQLException {
        String oracleFunction = """
            FUNCTION raise_custom_error RETURN TEXT IS
            BEGIN
              RAISE_APPLICATION_ERROR(-20001, 'Custom error: Invalid operation');
              RETURN 'Success';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed RAISE_APPLICATION_ERROR ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();
        assertFalse(transformed.contains("RAISE_APPLICATION_ERROR"), "Should not contain Oracle RAISE_APPLICATION_ERROR");
        assertTrue(transformed.contains("RAISE EXCEPTION"), "Should contain PostgreSQL RAISE EXCEPTION");
        assertTrue(transformed.contains("ERRCODE = 'P0001'"), "Should map -20001 to P0001");
        assertTrue(transformed.contains("HINT = 'Original Oracle error code: -20001'"), "Should preserve original error code in HINT");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Execute function - should raise exception
        Exception exception = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.raise_custom_error()");
        });

        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("Custom error: Invalid operation"), "Should contain custom error message");
    }

    /**
     * Tests RAISE_APPLICATION_ERROR with different error codes in valid range.
     * <p>
     * Verifies:
     * - Error codes -20000 to -20999 map correctly to P0001-P0999
     * - Formula: ERRCODE = 'P' + LPAD((oracle_code + 20000), 4, '0')
     */
    @Test
    void raiseApplicationError_errorCodeMapping() throws SQLException {
        String oracleFunction = """
            FUNCTION test_error_mapping(p_code NUMERIC) RETURN TEXT IS
            BEGIN
              IF p_code = 1 THEN
                RAISE_APPLICATION_ERROR(-20001, 'Error 1');
              ELSIF p_code = 55 THEN
                RAISE_APPLICATION_ERROR(-20055, 'Error 55');
              ELSIF p_code = 999 THEN
                RAISE_APPLICATION_ERROR(-20999, 'Error 999');
              END IF;
              RETURN 'No error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Error Code Mapping ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();
        assertTrue(transformed.contains("ERRCODE = 'P0001'"), "Should map -20001 to P0001");
        assertTrue(transformed.contains("ERRCODE = 'P0055'"), "Should map -20055 to P0055");
        assertTrue(transformed.contains("ERRCODE = 'P0999'"), "Should map -20999 to P0999");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Test error code 1
        Exception exception1 = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.test_error_mapping(1)");
        });
        assertTrue(exception1.getMessage().contains("Error 1"), "Should raise error 1");

        // Test error code 55
        Exception exception55 = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.test_error_mapping(55)");
        });
        assertTrue(exception55.getMessage().contains("Error 55"), "Should raise error 55");

        // Test error code 999
        Exception exception999 = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.test_error_mapping(999)");
        });
        assertTrue(exception999.getMessage().contains("Error 999"), "Should raise error 999");

        // Test no error
        List<Map<String, Object>> noErrorResult = executeQuery("SELECT hr.test_error_mapping(0) AS result");
        assertEquals(1, noErrorResult.size());
        assertEquals("No error", noErrorResult.get(0).get("result"), "Should return success message when no error");
    }

    /**
     * Tests RAISE_APPLICATION_ERROR with expression as message.
     * <p>
     * Verifies:
     * - Error message can be a string expression (concatenation)
     * - Dynamic error messages work correctly
     */
    @Test
    void raiseApplicationError_expressionMessage() throws SQLException {
        String oracleFunction = """
            FUNCTION check_salary(p_emp_id NUMERIC, p_salary NUMERIC) RETURN TEXT IS
            BEGIN
              IF p_salary < 0 THEN
                RAISE_APPLICATION_ERROR(-20001, 'Invalid salary ' || p_salary || ' for employee ' || p_emp_id);
              END IF;
              RETURN 'Salary OK';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Expression Message ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");
        assertTrue(result.getPostgresSql().contains("RAISE EXCEPTION"), "Should contain RAISE EXCEPTION");

        // Install and execute in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test with negative salary - should raise exception
        Exception exception = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.check_salary(123, -1000)");
        });

        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("Invalid salary"), "Should contain error message prefix");
        // Note: The exact formatting may differ between Oracle and PostgreSQL

        // Test with valid salary
        List<Map<String, Object>> validResult = executeQuery("SELECT hr.check_salary(123, 5000) AS result");
        assertEquals(1, validResult.size());
        assertEquals("Salary OK", validResult.get(0).get("result"), "Should return success for valid salary");
    }

    /**
     * Tests RAISE_APPLICATION_ERROR in EXCEPTION handler.
     * <p>
     * Verifies:
     * - RAISE_APPLICATION_ERROR works inside exception handlers
     * - Can re-raise with custom error code
     */
    @Test
    void raiseApplicationError_inExceptionHandler() throws SQLException {
        String oracleFunction = """
            FUNCTION safe_divide(p_dividend NUMERIC, p_divisor NUMERIC) RETURN NUMERIC IS
              v_result NUMERIC;
            BEGIN
              v_result := p_dividend / p_divisor;
              RETURN v_result;
            EXCEPTION
              WHEN ZERO_DIVIDE THEN
                RAISE_APPLICATION_ERROR(-20001, 'Division by zero detected for dividend: ' || p_dividend);
              WHEN OTHERS THEN
                RAISE_APPLICATION_ERROR(-20002, 'Unexpected error: ' || SQLERRM);
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed RAISE_APPLICATION_ERROR in EXCEPTION ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();
        assertTrue(transformed.contains("WHEN division_by_zero THEN"), "Should map ZERO_DIVIDE to division_by_zero");
        assertTrue(transformed.contains("RAISE EXCEPTION"), "Should contain RAISE EXCEPTION");
        assertTrue(transformed.contains("ERRCODE = 'P0001'"), "Should map -20001 to P0001");
        assertTrue(transformed.contains("ERRCODE = 'P0002'"), "Should map -20002 to P0002");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Test division by zero
        Exception exceptionZero = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.safe_divide(10, 0)");
        });
        assertTrue(exceptionZero.getMessage().contains("Division by zero detected"), "Should raise custom error for division by zero");

        // Test normal case
        List<Map<String, Object>> validResult = executeQuery("SELECT hr.safe_divide(10, 2) AS result");
        assertEquals(1, validResult.size());
        assertEquals(5.0, ((Number) validResult.get(0).get("result")).doubleValue(), 0.001);
    }
}
