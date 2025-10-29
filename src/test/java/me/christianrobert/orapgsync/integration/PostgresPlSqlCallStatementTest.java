package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify current behavior of function/procedure calls in PL/SQL blocks.
 *
 * <p>Tests demonstrate the MISSING functionality:
 * <ul>
 *   <li>call_statement visitor not implemented</li>
 *   <li>Synonym resolution for function/procedure calls not working</li>
 *   <li>PERFORM vs SELECT distinction not handled</li>
 * </ul>
 */
public class PostgresPlSqlCallStatementTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema and helper functions
        executeUpdate("CREATE SCHEMA hr");

        // Create a simple procedure
        executeUpdate("""
            CREATE OR REPLACE PROCEDURE hr.log_message(p_message TEXT)
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE NOTICE '%', p_message;
            END;
            $$
            """);

        // Create a simple function
        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.calculate_bonus(p_salary NUMERIC) RETURNS NUMERIC
            LANGUAGE plpgsql AS $$
            BEGIN
                RETURN p_salary * 0.10;
            END;
            $$
            """);
    }

    /**
     * Test 1: Simple procedure call (Oracle).
     *
     * <p>Oracle syntax: Just call the procedure by name
     * <p>PostgreSQL syntax: PERFORM procedure_name(args)
     *
     * <p>Expected: Should transform to PERFORM
     * <p>Actual: Transformation likely to fail (no call_statement visitor)
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
        System.out.println("=== Transformed Procedure Call ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==================================");

        if (!result.isSuccess()) {
            System.out.println("ERROR: " + result.getErrorMessage());
            fail("Transformation failed: " + result.getErrorMessage());
        }

        // TODO: Verify transformation includes PERFORM
        // Expected: PERFORM hr.log_message('Starting calculation');
    }

    /**
     * Test 2: Function call with assignment.
     *
     * <p>Oracle syntax: v_bonus := calculate_bonus(v_salary);
     * <p>PostgreSQL syntax: Same (assignment works for functions)
     *
     * <p>Expected: Should work (function call in expression)
     * <p>Actual: Should work (handled by VisitGeneralElement)
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

        if (!result.isSuccess()) {
            System.out.println("ERROR: " + result.getErrorMessage());
            fail("Transformation failed: " + result.getErrorMessage());
        }

        // TODO: Verify schema qualification
        // Expected: v_bonus := hr.calculate_bonus(p_salary);
    }

    /**
     * Test 3: Standalone function call (no assignment, just executing for side effects).
     *
     * <p>Oracle syntax: calculate_bonus(v_salary);
     * <p>PostgreSQL syntax: PERFORM calculate_bonus(v_salary); (or SELECT calculate_bonus(...) INTO NULL)
     *
     * <p>Expected: Should transform to PERFORM
     * <p>Actual: Transformation likely to fail (no call_statement visitor)
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

        if (!result.isSuccess()) {
            System.out.println("ERROR: " + result.getErrorMessage());
            fail("Transformation failed: " + result.getErrorMessage());
        }

        // TODO: Verify transformation includes PERFORM
        // Expected: PERFORM hr.calculate_bonus(p_salary);
    }

    /**
     * Test 4: Package procedure call (should flatten package.procedure to package__procedure).
     *
     * <p>Oracle syntax: pkg.proc(args);
     * <p>PostgreSQL syntax: PERFORM schema.pkg__proc(args);
     *
     * <p>Expected: Should flatten and use PERFORM
     * <p>Actual: Transformation likely to fail (no call_statement visitor)
     */
    @Test
    void packageProcedureCall_shouldFlattenAndUsePerform() throws SQLException {
        // Create a package function stub
        executeUpdate("""
            CREATE OR REPLACE FUNCTION hr.utilities__log(p_message TEXT) RETURNS VOID
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE NOTICE 'PKG: %', p_message;
            END;
            $$
            """);

        String oracleFunction = """
            FUNCTION test_package_call RETURN NUMBER IS
            BEGIN
              utilities.log('Test message');
              RETURN 1;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Package Procedure Call ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        if (!result.isSuccess()) {
            System.out.println("ERROR: " + result.getErrorMessage());
            fail("Transformation failed: " + result.getErrorMessage());
        }

        // TODO: Verify flattening and PERFORM
        // Expected: PERFORM hr.utilities__log('Test message');
    }
}
