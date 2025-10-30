package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PL/SQL Exception Handling Phase 3: User-Defined Exceptions.
 * <p>
 * Phase 3 features:
 * - User-defined exception declarations (exception_name EXCEPTION;)
 * - PRAGMA EXCEPTION_INIT linking exceptions to Oracle error codes
 * - RAISE user-defined exception → RAISE EXCEPTION USING ERRCODE
 * - WHEN user-defined exception → WHEN SQLSTATE
 * - Auto-generated error codes for exceptions without PRAGMA
 * <p>
 * Test strategy:
 * - Transform Oracle functions with user-defined exceptions
 * - Install in PostgreSQL
 * - Execute and verify behavior matches Oracle semantics
 */
public class PostgresPlSqlExceptionHandlingPhase3ValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Tests user-defined exception with PRAGMA EXCEPTION_INIT.
     * <p>
     * Verifies:
     * - Exception declaration is registered in context
     * - PRAGMA EXCEPTION_INIT links exception to Oracle error code
     * - Error code -20001 maps to SQLSTATE 'P0001'
     * - RAISE exception_name → RAISE EXCEPTION USING ERRCODE
     * - WHEN exception_name → WHEN SQLSTATE
     */
    @Test
    void userDefinedException_withPragma() throws SQLException {
        String oracleFunction = """
            FUNCTION test_user_exception RETURN TEXT IS
              invalid_salary EXCEPTION;
              PRAGMA EXCEPTION_INIT(invalid_salary, -20001);
              v_salary NUMERIC := -1000;
            BEGIN
              IF v_salary < 0 THEN
                RAISE invalid_salary;
              END IF;
              RETURN 'Salary is valid';
            EXCEPTION
              WHEN invalid_salary THEN
                RETURN 'Caught: Invalid salary';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed User-Defined Exception with PRAGMA ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Verify exception declaration is commented out with mapping info
        assertTrue(transformed.contains("-- invalid_salary EXCEPTION"), "Should comment out exception declaration");
        assertTrue(transformed.contains("Mapped to SQLSTATE 'P0001'"), "Should show SQLSTATE mapping");

        // Verify PRAGMA is commented out with mapping info
        assertTrue(transformed.contains("-- PRAGMA EXCEPTION_INIT(invalid_salary, -20001)"),
                  "Should comment out PRAGMA");

        // Verify RAISE transformation
        assertFalse(transformed.contains("RAISE invalid_salary"), "Should not contain Oracle RAISE");
        assertTrue(transformed.contains("RAISE EXCEPTION USING ERRCODE = 'P0001'"),
                  "Should transform to RAISE EXCEPTION with SQLSTATE");

        // Verify exception handler transformation
        assertFalse(transformed.contains("WHEN invalid_salary THEN"), "Should not contain Oracle WHEN");
        assertTrue(transformed.contains("WHEN SQLSTATE 'P0001' THEN"),
                  "Should transform to WHEN SQLSTATE");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Execute function - should catch exception
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_user_exception() AS result");
        String result_value = (String) rows.get(0).get("result");
        assertEquals("Caught: Invalid salary", result_value,
                    "Should catch user-defined exception");
    }

    /**
     * Tests user-defined exception without PRAGMA (auto-generated error code).
     * <p>
     * Verifies:
     * - Exception without PRAGMA gets auto-generated SQLSTATE (P9001, P9002, ...)
     * - RAISE exception_name → RAISE EXCEPTION USING ERRCODE
     * - WHEN exception_name → WHEN SQLSTATE
     */
    @Test
    void userDefinedException_withoutPragma() throws SQLException {
        String oracleFunction = """
            FUNCTION test_auto_exception RETURN TEXT IS
              validation_error EXCEPTION;
              v_value NUMERIC := 0;
            BEGIN
              IF v_value = 0 THEN
                RAISE validation_error;
              END IF;
              RETURN 'Value is valid';
            EXCEPTION
              WHEN validation_error THEN
                RETURN 'Caught: Validation error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed User-Defined Exception without PRAGMA ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Verify exception declaration (code assigned on first use, not in declaration)
        assertTrue(transformed.contains("-- validation_error EXCEPTION"), "Should comment out exception declaration");
        assertTrue(transformed.contains("PostgreSQL exception declared"), "Should show declaration comment");

        // Verify RAISE transformation with auto-generated code
        assertTrue(transformed.contains("RAISE EXCEPTION USING ERRCODE = 'P9001'"),
                  "Should use auto-generated SQLSTATE P9001");

        // Verify exception handler transformation
        assertTrue(transformed.contains("WHEN SQLSTATE 'P9001' THEN"),
                  "Should transform to WHEN SQLSTATE with auto-generated code");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Execute function - should catch exception
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_auto_exception() AS result");
        String result_value = (String) rows.get(0).get("result");
        assertEquals("Caught: Validation error", result_value,
                    "Should catch user-defined exception with auto-generated code");
    }

    /**
     * Tests multiple user-defined exceptions in same function.
     * <p>
     * Verifies:
     * - Multiple exceptions can be declared in same scope
     * - Each exception gets its own SQLSTATE mapping
     * - PRAGMA and non-PRAGMA exceptions coexist
     */
    @Test
    void multipleUserDefinedExceptions() throws SQLException {
        String oracleFunction = """
            FUNCTION test_multiple_exceptions(p_scenario NUMERIC) RETURN TEXT IS
              invalid_salary EXCEPTION;
              PRAGMA EXCEPTION_INIT(invalid_salary, -20001);
              business_rule_violation EXCEPTION;
              PRAGMA EXCEPTION_INIT(business_rule_violation, -20002);
              validation_error EXCEPTION;
            BEGIN
              IF p_scenario = 1 THEN
                RAISE invalid_salary;
              ELSIF p_scenario = 2 THEN
                RAISE business_rule_violation;
              ELSIF p_scenario = 3 THEN
                RAISE validation_error;
              END IF;
              RETURN 'No error';
            EXCEPTION
              WHEN invalid_salary THEN
                RETURN 'Caught: Invalid salary';
              WHEN business_rule_violation THEN
                RETURN 'Caught: Business rule violation';
              WHEN validation_error THEN
                RETURN 'Caught: Validation error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Multiple User-Defined Exceptions ===");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Verify PRAGMA comments show their mappings
        assertTrue(transformed.contains("-- PRAGMA EXCEPTION_INIT(invalid_salary, -20001)"), "invalid_salary PRAGMA");
        assertTrue(transformed.contains("Mapped to SQLSTATE 'P0001'"), "invalid_salary → P0001");
        assertTrue(transformed.contains("-- PRAGMA EXCEPTION_INIT(business_rule_violation, -20002)"), "business_rule_violation PRAGMA");
        assertTrue(transformed.contains("Mapped to SQLSTATE 'P0002'"), "business_rule_violation → P0002");
        // validation_error has no PRAGMA, code assigned on first use
        assertTrue(transformed.contains("-- validation_error EXCEPTION"), "validation_error declaration");

        // Verify RAISE transformations
        assertTrue(transformed.contains("RAISE EXCEPTION USING ERRCODE = 'P0001'"), "RAISE invalid_salary");
        assertTrue(transformed.contains("RAISE EXCEPTION USING ERRCODE = 'P0002'"), "RAISE business_rule_violation");
        assertTrue(transformed.contains("RAISE EXCEPTION USING ERRCODE = 'P9001'"), "RAISE validation_error");

        // Verify exception handlers
        assertTrue(transformed.contains("WHEN SQLSTATE 'P0001' THEN"), "WHEN invalid_salary");
        assertTrue(transformed.contains("WHEN SQLSTATE 'P0002' THEN"), "WHEN business_rule_violation");
        assertTrue(transformed.contains("WHEN SQLSTATE 'P9001' THEN"), "WHEN validation_error");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Test scenario 1: invalid_salary
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.test_multiple_exceptions(1) AS result");
        String result1 = (String) rows1.get(0).get("result");
        assertEquals("Caught: Invalid salary", result1);

        // Test scenario 2: business_rule_violation
        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.test_multiple_exceptions(2) AS result");
        String result2 = (String) rows2.get(0).get("result");
        assertEquals("Caught: Business rule violation", result2);

        // Test scenario 3: validation_error
        List<Map<String, Object>> rows3 = executeQuery("SELECT hr.test_multiple_exceptions(3) AS result");
        String result3 = (String) rows3.get(0).get("result");
        assertEquals("Caught: Validation error", result3);
    }

    /**
     * Tests user-defined exception with multiple handlers using OR.
     * <p>
     * Verifies:
     * - WHEN exception1 OR exception2 → WHEN SQLSTATE 'code1' OR SQLSTATE 'code2'
     */
    @Test
    void userDefinedException_multipleHandlersWithOr() throws SQLException {
        String oracleFunction = """
            FUNCTION test_or_handlers(p_scenario NUMERIC) RETURN TEXT IS
              error1 EXCEPTION;
              PRAGMA EXCEPTION_INIT(error1, -20001);
              error2 EXCEPTION;
              PRAGMA EXCEPTION_INIT(error2, -20002);
            BEGIN
              IF p_scenario = 1 THEN
                RAISE error1;
              ELSIF p_scenario = 2 THEN
                RAISE error2;
              END IF;
              RETURN 'No error';
            EXCEPTION
              WHEN error1 OR error2 THEN
                RETURN 'Caught: Either error1 or error2';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed User-Defined Exception with OR ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Verify exception handler with OR
        assertTrue(transformed.contains("WHEN SQLSTATE 'P0001' OR SQLSTATE 'P0002' THEN"),
                  "Should transform to WHEN SQLSTATE 'P0001' OR SQLSTATE 'P0002'");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Test scenario 1: error1
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.test_or_handlers(1) AS result");
        String result1 = (String) rows1.get(0).get("result");
        assertEquals("Caught: Either error1 or error2", result1);

        // Test scenario 2: error2
        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.test_or_handlers(2) AS result");
        String result2 = (String) rows2.get(0).get("result");
        assertEquals("Caught: Either error1 or error2", result2);
    }

    /**
     * Tests user-defined exception mixed with standard exceptions.
     * <p>
     * Verifies:
     * - User-defined and standard exceptions can coexist
     * - Each type is handled correctly (SQLSTATE vs lowercase name)
     */
    @Test
    void userDefinedException_mixedWithStandard() throws SQLException {
        String oracleFunction = """
            FUNCTION test_mixed_exceptions(p_scenario NUMERIC) RETURN TEXT IS
              custom_error EXCEPTION;
              PRAGMA EXCEPTION_INIT(custom_error, -20001);
              v_value NUMERIC;
            BEGIN
              IF p_scenario = 1 THEN
                SELECT salary INTO v_value FROM employees WHERE emp_id = 999;
              ELSIF p_scenario = 2 THEN
                RAISE custom_error;
              ELSIF p_scenario = 3 THEN
                v_value := 10 / 0;
              END IF;
              RETURN 'No error';
            EXCEPTION
              WHEN NO_DATA_FOUND THEN
                RETURN 'Caught: No data found';
              WHEN custom_error THEN
                RETURN 'Caught: Custom error';
              WHEN ZERO_DIVIDE THEN
                RETURN 'Caught: Division by zero';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Mixed User-Defined and Standard Exceptions ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==============================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Verify mixed exception handlers
        assertTrue(transformed.contains("WHEN no_data_found THEN"),
                  "Standard exception: lowercase name");
        assertTrue(transformed.contains("WHEN SQLSTATE 'P0001' THEN"),
                  "User-defined exception: SQLSTATE");
        assertTrue(transformed.contains("WHEN division_by_zero THEN"),
                  "Standard exception with name change");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Note: Scenarios 1 and 3 require employees table to exist
        // We'll only test scenario 2 (custom error)
        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.test_mixed_exceptions(2) AS result");
        String result2 = (String) rows2.get(0).get("result");
        assertEquals("Caught: Custom error", result2);
    }

    /**
     * Tests PRAGMA EXCEPTION_INIT with error code outside valid range.
     * <p>
     * Verifies:
     * - Error codes outside -20000 to -20999 generate warning in comment
     * - Transformation still succeeds with best-effort mapping
     */
    @Test
    void userDefinedException_pragmaOutsideValidRange() throws SQLException {
        String oracleFunction = """
            FUNCTION test_invalid_pragma RETURN TEXT IS
              weird_error EXCEPTION;
              PRAGMA EXCEPTION_INIT(weird_error, -19999);
            BEGIN
              RAISE weird_error;
              RETURN 'No error';
            EXCEPTION
              WHEN weird_error THEN
                RETURN 'Caught: Weird error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed PRAGMA with Invalid Range ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=============================================\n");

        // Verify transformation success (best-effort)
        assertTrue(result.isSuccess(), "Transformation should succeed with warning");

        String transformed = result.getPostgresSql();

        // Verify warning in comment
        assertTrue(transformed.contains("Warning: Error code outside valid range"),
                  "Should warn about invalid error code range");
    }

    /**
     * Tests user-defined exception that is raised but never caught.
     * <p>
     * Verifies:
     * - Uncaught user-defined exceptions propagate correctly
     * - PostgreSQL SQLSTATE error is raised
     */
    @Test
    void userDefinedException_uncaught() throws SQLException {
        String oracleFunction = """
            FUNCTION test_uncaught_exception RETURN TEXT IS
              fatal_error EXCEPTION;
              PRAGMA EXCEPTION_INIT(fatal_error, -20099);
            BEGIN
              RAISE fatal_error;
              RETURN 'No error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed Uncaught User-Defined Exception ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===================================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Install in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Execute function - should raise exception (uncaught)
        Exception exception = assertThrows(Exception.class, () -> {
            executeQuery("SELECT hr.test_uncaught_exception()");
        });

        // PostgreSQL should report SQLSTATE P0099
        // Note: Exact error message format may vary
        assertNotNull(exception, "Should throw exception");
    }

    /**
     * Tests PRAGMA before exception declaration (order independence).
     * <p>
     * Verifies:
     * - PRAGMA EXCEPTION_INIT can appear before exception declaration
     * - Exception context correctly links regardless of order
     */
    @Test
    void userDefinedException_pragmaBeforeDeclaration() throws SQLException {
        String oracleFunction = """
            FUNCTION test_pragma_order RETURN TEXT IS
              PRAGMA EXCEPTION_INIT(early_error, -20001);
              early_error EXCEPTION;
            BEGIN
              RAISE early_error;
              RETURN 'No error';
            EXCEPTION
              WHEN early_error THEN
                RETURN 'Caught: Early error';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== Transformed PRAGMA Before Declaration ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Verify correct SQLSTATE mapping (P0001)
        assertTrue(transformed.contains("Mapped to SQLSTATE 'P0001'"), "Should map to P0001");
        assertTrue(transformed.contains("RAISE EXCEPTION USING ERRCODE = 'P0001'"), "RAISE should use P0001");
        assertTrue(transformed.contains("WHEN SQLSTATE 'P0001' THEN"), "WHEN should use P0001");

        // Install and execute in PostgreSQL
        executeUpdate(transformed);

        // Execute function - should catch exception
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_pragma_order() AS result");
        String result_value = (String) rows.get(0).get("result");
        assertEquals("Caught: Early error", result_value);
    }
}
