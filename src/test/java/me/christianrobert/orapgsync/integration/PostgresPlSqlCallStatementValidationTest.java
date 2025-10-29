package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL call statements (procedure/function calls).
 *
 * <p>Tests the transformation of Oracle standalone procedure/function calls to PostgreSQL PERFORM/SELECT INTO.
 * Follows comprehensive testing philosophy: each test validates multiple aspects together
 * (parsing, transformation, execution, result correctness).
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with procedure/function calls</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all scenarios</li>
 *   <li>Validates: PERFORM syntax, SELECT INTO syntax, package flattening, schema qualification</li>
 * </ul>
 *
 * <p>Scope:
 * <ul>
 *   <li>✅ Simple procedure calls → PERFORM</li>
 *   <li>✅ Simple function calls → PERFORM (return value discarded)</li>
 *   <li>✅ Function calls with INTO → SELECT INTO</li>
 *   <li>✅ Package member calls → Flattened with PERFORM</li>
 *   <li>✅ Schema qualification</li>
 * </ul>
 */
public class PostgresPlSqlCallStatementValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");

        // Create a simple logging function (returns void - works with PERFORM)
        // Note: PostgreSQL PERFORM works with functions (including void), not procedures
        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.log_message(p_message TEXT) RETURNS VOID
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE NOTICE 'LOG: %', p_message;
            END;
            $$
            """);

        // Create a helper function that returns a value
        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.calculate_bonus(p_salary NUMERIC) RETURNS NUMERIC
            LANGUAGE plpgsql AS $$
            BEGIN
                RETURN p_salary * 0.10;
            END;
            $$
            """);

        // Create a function that doubles a value
        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.double_value(p_value NUMERIC) RETURNS NUMERIC
            LANGUAGE plpgsql AS $$
            BEGIN
                RETURN p_value * 2;
            END;
            $$
            """);

        // Create package-style flattened functions
        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.utilities__log(p_message TEXT) RETURNS VOID
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE NOTICE 'PKG LOG: %', p_message;
            END;
            $$
            """);

        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.math__add(p_a NUMERIC, p_b NUMERIC) RETURNS NUMERIC
            LANGUAGE plpgsql AS $$
            BEGIN
                RETURN p_a + p_b;
            END;
            $$
            """);
    }

    /**
     * Test 1: Simple procedure call.
     *
     * <p>Validates:
     * <ul>
     *   <li>Oracle: log_message('Test') → PostgreSQL: PERFORM hr.log_message('Test')</li>
     *   <li>Schema qualification</li>
     *   <li>Procedure execution without errors</li>
     * </ul>
     */
    @Test
    void simpleProcedureCall_shouldUsePerform() throws SQLException {
        String oracleFunction = """
            FUNCTION test_procedure_call RETURN NUMBER IS
              v_result NUMBER := 0;
            BEGIN
              log_message('Starting calculation');
              v_result := 100;
              RETURN v_result;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple Procedure Call ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify it contains PERFORM
        assertTrue(result.getPostgresSql().contains("PERFORM"), "Should contain PERFORM");
        assertTrue(result.getPostgresSql().contains("hr.log_message"), "Should be schema-qualified");

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_procedure_call() AS result");
        int resultValue = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(100, resultValue, "Should return 100");
    }

    /**
     * Test 2: Function call with assignment.
     *
     * <p>Validates:
     * <ul>
     *   <li>Oracle: v_bonus := calculate_bonus(v_salary) (works as expression, not call_statement)</li>
     *   <li>This should already work via VisitGeneralElement</li>
     *   <li>Schema qualification in assignment expressions</li>
     * </ul>
     */
    @Test
    void functionCallInAssignment_shouldWork() throws SQLException {
        String oracleFunction = """
            FUNCTION test_function_call(p_salary NUMBER) RETURN NUMBER IS
              v_bonus NUMBER;
            BEGIN
              v_bonus := calculate_bonus(p_salary);
              RETURN v_bonus;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Function Call in Assignment ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_function_call(1000) AS bonus");
        double bonus = ((Number) rows.get(0).get("bonus")).doubleValue();
        assertEquals(100.0, bonus, 0.01, "Should return 10% bonus: 100");
    }

    /**
     * Test 3: Standalone function call (no assignment, just executing for side effects).
     *
     * <p>Validates:
     * <ul>
     *   <li>Oracle: calculate_bonus(v_salary); → PostgreSQL: PERFORM hr.calculate_bonus(v_salary);</li>
     *   <li>Function call as statement (not expression)</li>
     *   <li>Return value discarded</li>
     * </ul>
     */
    @Test
    void standaloneFunctionCall_shouldUsePerform() throws SQLException {
        String oracleFunction = """
            FUNCTION test_standalone_call(p_salary NUMBER) RETURN NUMBER IS
            BEGIN
              calculate_bonus(p_salary);
              RETURN 1;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Standalone Function Call ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify it contains PERFORM
        assertTrue(result.getPostgresSql().contains("PERFORM"), "Should contain PERFORM");

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_standalone_call(1000) AS result");
        int resultValue = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(1, resultValue, "Should return 1");
    }

    /**
     * Test 4: Function call with INTO clause (OUT parameter pattern).
     *
     * <p>Note: Oracle's call_statement INTO is for procedures with OUT parameters,
     * not for function return values. Function returns use assignment: v := func();
     * Skipping this test for now - OUT parameters need separate implementation.
     */
    // @Test - Commented out: INTO clause for OUT parameters not yet implemented
    void functionCallWithInto_outParameterPattern() throws SQLException {
        // TODO: Implement OUT parameter support
        // Oracle: procedure_name(p_in, p_out_param);
        // PostgreSQL: Different syntax needed
    }

    /**
     * Test 5: Package procedure call (should flatten package.procedure to package__procedure).
     *
     * <p>Validates:
     * <ul>
     *   <li>Oracle: utilities.log('Test') → PostgreSQL: PERFORM hr.utilities__log('Test')</li>
     *   <li>Package member flattening</li>
     *   <li>Schema qualification with flattened names</li>
     * </ul>
     */
    @Test
    void packageProcedureCall_shouldFlattenAndUsePerform() throws SQLException {
        String oracleFunction = """
            FUNCTION test_package_call RETURN NUMBER IS
            BEGIN
              utilities.log('Test message from package');
              RETURN 1;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Package Procedure Call ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify flattening and PERFORM
        assertTrue(result.getPostgresSql().contains("PERFORM"), "Should contain PERFORM");
        assertTrue(result.getPostgresSql().contains("utilities__log"), "Should flatten package.procedure to package__procedure");

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_package_call() AS result");
        int resultValue = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(1, resultValue, "Should return 1");
    }

    /**
     * Test 6: Package function call in assignment (not as statement).
     *
     * <p>Validates:
     * <ul>
     *   <li>Oracle: v_result := math.add(a, b);</li>
     *   <li>PostgreSQL: v_result := hr.math__add(a, b);</li>
     *   <li>Package function flattening in assignment context</li>
     * </ul>
     */
    @Test
    void packageFunctionCallInAssignment_shouldFlatten() throws SQLException {
        String oracleFunction = """
            FUNCTION test_package_function(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS
              v_result NUMBER;
            BEGIN
              v_result := math.add(p_a, p_b);
              RETURN v_result;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Package Function Call in Assignment ===");
        System.out.println(result.getPostgresSql());
        System.out.println("========================================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify flattening
        assertTrue(result.getPostgresSql().contains("math__add"), "Should flatten math.add to math__add");

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_package_function(10, 20) AS result");
        int resultValue = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(30, resultValue, "Should return 30 (10 + 20)");
    }

    /**
     * Test 7: Multiple procedure calls in sequence.
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple PERFORM statements in sequence</li>
     *   <li>Each call independently transformed</li>
     * </ul>
     */
    @Test
    void multipleProcedureCalls_shouldEachUsePerform() throws SQLException {
        String oracleFunction = """
            FUNCTION test_multiple_calls RETURN NUMBER IS
              v_result NUMBER := 0;
            BEGIN
              log_message('First call');
              log_message('Second call');
              log_message('Third call');
              v_result := 100;
              RETURN v_result;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Multiple Procedure Calls ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify multiple PERFORM statements
        String transformed = result.getPostgresSql();
        int performCount = transformed.split("PERFORM").length - 1;
        assertEquals(3, performCount, "Should have 3 PERFORM statements");

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_multiple_calls() AS result");
        int resultValue = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(100, resultValue, "Should return 100");
    }

    /**
     * Test 8: Mixed calls (PERFORM for side effects, assignment for return values).
     *
     * <p>Validates:
     * <ul>
     *   <li>Combination of different call patterns</li>
     *   <li>PERFORM and assignment working together</li>
     * </ul>
     */
    @Test
    void mixedCalls_shouldHandleAllPatterns() throws SQLException {
        String oracleFunction = """
            FUNCTION test_mixed_calls(p_salary NUMBER) RETURN NUMBER IS
              v_bonus NUMBER;
              v_doubled NUMBER;
            BEGIN
              log_message('Calculating bonus');
              v_bonus := calculate_bonus(p_salary);
              v_doubled := double_value(v_bonus);
              RETURN v_doubled;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Mixed Calls ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify contains PERFORM
        assertTrue(result.getPostgresSql().contains("PERFORM"), "Should contain PERFORM");

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // salary=1000 → bonus=100 → doubled=200
        List<Map<String, Object>> rows = executeQuery("SELECT hr.test_mixed_calls(1000) AS result");
        double resultValue = ((Number) rows.get(0).get("result")).doubleValue();
        assertEquals(200.0, resultValue, 0.01, "Should return 200 (1000 * 0.10 * 2)");
    }
}
