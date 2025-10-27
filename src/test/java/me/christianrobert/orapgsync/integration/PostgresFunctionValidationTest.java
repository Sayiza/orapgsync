package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PL/SQL function transformation that execute against real PostgreSQL.
 *
 * <p>These tests verify the complete workflow:
 * <ol>
 *   <li>Transform Oracle PL/SQL function body → PostgreSQL PL/pgSQL body</li>
 *   <li>Wrap in CREATE FUNCTION statement</li>
 *   <li>Execute CREATE FUNCTION on real PostgreSQL database</li>
 *   <li>Call the function and verify it returns expected results</li>
 * </ol>
 *
 * <p><b>Philosophy</b>: Start with the simplest possible functions and incrementally
 * add complexity. Each test proves the function actually executes correctly, not just
 * that it transforms syntactically.
 *
 * <p><b>Test Pattern</b>:
 * <pre>{@code
 * 1. Transform function body using TransformationService
 * 2. Wrap body in CREATE FUNCTION with proper signature
 * 3. Execute CREATE FUNCTION on PostgreSQL
 * 4. Call function: SELECT function_name()
 * 5. Assert result matches expected value
 * }</pre>
 */
public class PostgresFunctionValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    @Override
    void setup() throws SQLException {
        super.setup();

        // Create test schema
        executeUpdate("CREATE SCHEMA IF NOT EXISTS hr");
    }

    // ========== SIMPLEST FUNCTION: RETURN NULL ==========

    @Test
    void simplestFunction_returnNull() throws SQLException {
        // Given: Oracle function that returns NULL
        String oracleFunctionBody = """
            FUNCTION get_test RETURN NUMBER IS
            BEGIN
              RETURN NULL;
            END;
            """;

        // When: Transform function body (function name extracted from AST)
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody, "hr", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            "Transformation should succeed. Error: " + result.getErrorMessage());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        String createFunction = result.getPostgresSql();
        System.out.println("\n=== TEST: simplestFunction_returnNull ===");
        System.out.println("Transformed SQL:\n" + createFunction);
        System.out.println("==========================================\n");

        // Execute CREATE FUNCTION on PostgreSQL
        executeUpdate(createFunction);

        // Call function and verify result
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_test() AS result");
        assertRowCount(1, rows);
        assertColumnValue(rows, 0, "result", null); // Should return NULL
    }

    // ========== RETURN LITERAL NUMBER ==========

    @Test
    void simpleFunction_returnLiteral() throws SQLException {
        // Given: Oracle function that returns literal 42
        String oracleFunctionBody = """
            FUNCTION get_constant RETURN NUMBER IS
            BEGIN
              RETURN 42;
            END;
            """;

        // When: Transform function body (function name extracted from AST)
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody, "hr", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            "Transformation should succeed. Error: " + result.getErrorMessage());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        String createFunction = result.getPostgresSql();
        System.out.println("\n=== TEST: simpleFunction_returnLiteral ===");
        System.out.println("Transformed SQL:\n" + createFunction);
        System.out.println("==========================================\n");

        // Execute CREATE FUNCTION on PostgreSQL
        executeUpdate(createFunction);

        // Call function and verify result
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_constant() AS result");
        assertRowCount(1, rows);

        // PostgreSQL returns BigDecimal for numeric, compare as Number
        Object resultValue = rows.get(0).get("result");
        assertNotNull(resultValue, "Result should not be NULL");
        assertEquals(42, ((Number) resultValue).intValue(), "Function should return 42");
    }

    // ========== RETURN STRING LITERAL ==========

    @Test
    void simpleFunction_returnString() throws SQLException {
        // Given: Oracle function that returns string
        String oracleFunctionBody = """
            FUNCTION get_greeting RETURN VARCHAR2 IS
            BEGIN
              RETURN 'Hello World';
            END;
            """;

        // When: Transform function body (function name extracted from AST)
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody, "hr", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            "Transformation should succeed. Error: " + result.getErrorMessage());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        String createFunction = result.getPostgresSql();
        System.out.println("\n=== TEST: simpleFunction_returnString ===");
        System.out.println("Transformed SQL:\n" + createFunction);
        System.out.println("=========================================\n");

        // Execute CREATE FUNCTION on PostgreSQL
        executeUpdate(createFunction);

        // Call function and verify result
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_greeting() AS result");
        assertRowCount(1, rows);
        assertColumnValue(rows, 0, "result", "Hello World");
    }

    // ========== RETURN ARITHMETIC EXPRESSION ==========

    @Test
    void simpleFunction_returnExpression() throws SQLException {
        // Given: Oracle function that returns arithmetic expression
        String oracleFunctionBody = """
            FUNCTION calculate RETURN NUMBER IS
            BEGIN
              RETURN 2 + 2;
            END;
            """;

        // When: Transform function body (function name extracted from AST)
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody, "hr", indices);

        // Then: Transformation succeeds
        assertTrue(result.isSuccess(),
            "Transformation should succeed. Error: " + result.getErrorMessage());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        String createFunction = result.getPostgresSql();
        System.out.println("\n=== TEST: simpleFunction_returnExpression ===");
        System.out.println("Transformed SQL:\n" + createFunction);
        System.out.println("=============================================\n");

        // Execute CREATE FUNCTION on PostgreSQL
        executeUpdate(createFunction);

        // Call function and verify result
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate() AS result");
        assertRowCount(1, rows);

        Object resultValue = rows.get(0).get("result");
        assertNotNull(resultValue, "Result should not be NULL");
        assertEquals(4, ((Number) resultValue).intValue(), "Function should return 4 (2+2)");
    }

    // ========== VERIFY MULTIPLE FUNCTIONS CAN COEXIST ==========

    @Test
    void multipleFunctions_canCoexist() throws SQLException {
        // Test that we can create and call multiple functions in the same schema

        // Create function 1: return NULL
        String func1Body = """
            FUNCTION func1 RETURN NUMBER IS
            BEGIN
              RETURN NULL;
            END;
            """;

        TransformationResult result1 = transformationService.transformFunction(func1Body, "hr", indices);
        assertTrue(result1.isSuccess());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        executeUpdate(result1.getPostgresSql());

        // Create function 2: return 100
        String func2Body = """
            FUNCTION func2 RETURN NUMBER IS
            BEGIN
              RETURN 100;
            END;
            """;

        TransformationResult result2 = transformationService.transformFunction(func2Body, "hr", indices);
        assertTrue(result2.isSuccess());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        executeUpdate(result2.getPostgresSql());

        // Verify both functions work
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.func1() AS result");
        assertRowCount(1, rows1);
        assertColumnValue(rows1, 0, "result", null);

        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.func2() AS result");
        assertRowCount(1, rows2);
        assertEquals(100, ((Number) rows2.get(0).get("result")).intValue());
    }

    // ========== VERIFY CREATE OR REPLACE WORKS ==========

    @Test
    void createOrReplace_updatesFunction() throws SQLException {
        // Create initial function that returns 1
        String initialBody = """
            FUNCTION counter RETURN NUMBER IS
            BEGIN
              RETURN 1;
            END;
            """;

        TransformationResult result1 = transformationService.transformFunction(initialBody, "hr", indices);
        assertTrue(result1.isSuccess());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        executeUpdate(result1.getPostgresSql());

        // Verify initial function returns 1
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.counter() AS result");
        assertEquals(1, ((Number) rows1.get(0).get("result")).intValue());

        // Replace function to return 2
        String updatedBody = """
            FUNCTION counter RETURN NUMBER IS
            BEGIN
              RETURN 2;
            END;
            """;

        TransformationResult result2 = transformationService.transformFunction(updatedBody, "hr", indices);
        assertTrue(result2.isSuccess());

        // Use result directly - it's already a complete CREATE OR REPLACE FUNCTION statement
        executeUpdate(result2.getPostgresSql());

        // Verify updated function returns 2
        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.counter() AS result");
        assertEquals(2, ((Number) rows2.get(0).get("result")).intValue());
    }
}
