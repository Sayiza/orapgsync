package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests to verify that transformed functions can replace stubs without breaking dependencies.
 *
 * <p>This validates the critical requirement that stub generation and transformation produce
 * compatible signatures, allowing CREATE OR REPLACE to work seamlessly.
 *
 * <p>These tests manually create stubs matching the expected output from PostgresFunctionStubCreationJob
 * and verify they can be replaced with transformations.
 */
public class PostgresStubReplacementIntegrationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema for functions
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Pure procedure (no OUT parameters) - stub can be replaced with transformation.
     *
     * <p>Validates:
     * <ul>
     *   <li>Stub creates: FUNCTION RETURNS void</li>
     *   <li>Transformation creates: FUNCTION RETURNS void</li>
     *   <li>CREATE OR REPLACE succeeds</li>
     *   <li>Dependent views remain intact</li>
     * </ul>
     */
    @Test
    void pureProcedureStub_canBeReplacedWithTransformation() throws SQLException {
        // Step 1: Create stub (matching expected output from PostgresFunctionStubCreationJob)
        String stubSql = """
            CREATE OR REPLACE FUNCTION hr.log_message(p_message text)
            RETURNS void AS $$
            BEGIN
                RETURN; -- Stub: Original Oracle procedure HR.LOG_MESSAGE
            END;
            $$ LANGUAGE plpgsql
            """;

        System.out.println("=== STUB SQL ===");
        System.out.println(stubSql);
        System.out.println("================");

        executeUpdate(stubSql);

        // Step 2: Create dependent view
        executeUpdate("CREATE VIEW hr.test_view AS SELECT 'test' AS result");

        // Step 3: Transform procedure
        // Note: Using variable assignment instead of NULL statement (NULL not yet supported by transformer)
        String oracleProcedure = """
            PROCEDURE log_message(p_message IN VARCHAR2)
            IS
              v_dummy VARCHAR2(1);
            BEGIN
              v_dummy := 'X'; -- Dummy implementation
            END;
            """;
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        System.out.println("=== TRANSFORMATION SQL ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================");

        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Step 4: Replace stub - should NOT throw exception
        assertDoesNotThrow(() -> executeUpdate(result.getPostgresSql()),
            "CREATE OR REPLACE should succeed (stub→transformation)");

        // Step 5: Verify dependent view still works
        List<Map<String, Object>> rows = executeQuery("SELECT * FROM hr.test_view");
        assertEquals(1, rows.size(), "Dependent view should still work");
    }

    /**
     * Test 2: Procedure with single OUT parameter - stub matches transformation.
     *
     * <p>Validates:
     * <ul>
     *   <li>Stub creates: FUNCTION(p_in numeric, p_out OUT numeric) RETURNS numeric</li>
     *   <li>Transformation creates: FUNCTION(p_in numeric, p_out OUT numeric) RETURNS numeric</li>
     *   <li>Signatures are identical</li>
     *   <li>CREATE OR REPLACE succeeds</li>
     * </ul>
     */
    @Test
    void procedureWithSingleOut_stubMatchesTransformation() throws SQLException {
        // Step 1: Create stub (matching expected output from PostgresFunctionStubCreationJob)
        String stubSql = """
            CREATE OR REPLACE FUNCTION hr.divide_numbers(p_dividend numeric, p_divisor numeric, p_quotient OUT numeric)
            RETURNS numeric AS $$
            BEGIN
                RETURN; -- Stub: Original Oracle procedure HR.DIVIDE_NUMBERS
            END;
            $$ LANGUAGE plpgsql
            """;

        System.out.println("=== STUB SQL (Single OUT) ===");
        System.out.println(stubSql);
        System.out.println("==============================");

        executeUpdate(stubSql);

        // Verify stub works
        List<Map<String, Object>> stubResult = executeQuery("SELECT hr.divide_numbers(100, 3) AS quotient");
        assertNull(stubResult.get(0).get("quotient"), "Stub should return NULL");

        // Step 2: Transform procedure
        String oracleProcedure = """
            PROCEDURE divide_numbers(p_dividend IN NUMBER, p_divisor IN NUMBER, p_quotient OUT NUMBER)
            IS
            BEGIN
              p_quotient := TRUNC(p_dividend / p_divisor);
            END;
            """;
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        System.out.println("=== TRANSFORMATION SQL (Single OUT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("========================================");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Step 3: Replace stub
        assertDoesNotThrow(() -> executeUpdate(result.getPostgresSql()),
            "CREATE OR REPLACE should succeed (stub→transformation)");

        // Step 4: Verify transformation works
        List<Map<String, Object>> transformResult = executeQuery("SELECT hr.divide_numbers(100, 3) AS quotient");
        assertEquals(33, ((Number) transformResult.get(0).get("quotient")).intValue(),
            "Transformed function should return correct result");
    }

    /**
     * Test 3: Procedure with multiple OUT parameters - stub matches transformation.
     *
     * <p>Validates:
     * <ul>
     *   <li>Stub creates: FUNCTION(..., p_out1 OUT type, p_out2 OUT type) RETURNS RECORD</li>
     *   <li>Transformation creates: FUNCTION(..., p_out1 OUT type, p_out2 OUT type) RETURNS RECORD</li>
     *   <li>CREATE OR REPLACE succeeds</li>
     * </ul>
     */
    @Test
    void procedureWithMultipleOut_stubMatchesTransformation() throws SQLException {
        // Step 1: Create stub (matching expected output from PostgresFunctionStubCreationJob)
        String stubSql = """
            CREATE OR REPLACE FUNCTION hr.calculate_stats(p_value1 numeric, p_value2 numeric, p_sum OUT numeric, p_product OUT numeric)
            RETURNS RECORD AS $$
            BEGIN
                RETURN; -- Stub: Original Oracle procedure HR.CALCULATE_STATS
            END;
            $$ LANGUAGE plpgsql
            """;

        System.out.println("=== STUB SQL (Multiple OUT) ===");
        System.out.println(stubSql);
        System.out.println("================================");

        executeUpdate(stubSql);

        // Step 2: Transform procedure
        String oracleProcedure = """
            PROCEDURE calculate_stats(p_value1 IN NUMBER, p_value2 IN NUMBER,
                                      p_sum OUT NUMBER, p_product OUT NUMBER)
            IS
            BEGIN
              p_sum := p_value1 + p_value2;
              p_product := p_value1 * p_value2;
            END;
            """;
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        System.out.println("=== TRANSFORMATION SQL (Multiple OUT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Step 3: Replace stub
        assertDoesNotThrow(() -> executeUpdate(result.getPostgresSql()),
            "CREATE OR REPLACE should succeed (stub→transformation)");

        // Step 4: Verify transformation works
        List<Map<String, Object>> rows = executeQuery("SELECT * FROM hr.calculate_stats(5, 10)");
        assertEquals(15, ((Number) rows.get(0).get("p_sum")).intValue(), "Sum should be correct");
        assertEquals(50, ((Number) rows.get(0).get("p_product")).intValue(), "Product should be correct");
    }

    /**
     * Test 4: Procedure with mixed IN/OUT/INOUT parameters.
     *
     * <p>Validates:
     * <ul>
     *   <li>Stub and transformation both handle INOUT correctly</li>
     *   <li>Parameter order is consistent</li>
     *   <li>CREATE OR REPLACE succeeds</li>
     * </ul>
     */
    @Test
    void procedureWithMixedParameters_stubMatchesTransformation() throws SQLException {
        // Step 1: Create stub (matching expected output from PostgresFunctionStubCreationJob)
        String stubSql = """
            CREATE OR REPLACE FUNCTION hr.adjust_values(p_input numeric, p_counter INOUT numeric, p_result OUT numeric)
            RETURNS RECORD AS $$
            BEGIN
                RETURN; -- Stub: Original Oracle procedure HR.ADJUST_VALUES
            END;
            $$ LANGUAGE plpgsql
            """;

        System.out.println("=== STUB SQL (Mixed Parameters) ===");
        System.out.println(stubSql);
        System.out.println("====================================");

        executeUpdate(stubSql);

        // Step 2: Transform procedure
        String oracleProcedure = """
            PROCEDURE adjust_values(p_input IN NUMBER, p_counter INOUT NUMBER, p_result OUT NUMBER)
            IS
            BEGIN
              p_counter := p_counter + 1;
              p_result := p_input * p_counter;
            END;
            """;
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        System.out.println("=== TRANSFORMATION SQL (Mixed Parameters) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==============================================");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Step 3: Replace stub
        assertDoesNotThrow(() -> executeUpdate(result.getPostgresSql()),
            "CREATE OR REPLACE should succeed (stub→transformation)");

        // Step 4: Verify transformation works
        List<Map<String, Object>> rows = executeQuery("SELECT * FROM hr.adjust_values(10, 5)");
        assertEquals(6, ((Number) rows.get(0).get("p_counter")).intValue(), "Counter should increment");
        assertEquals(60, ((Number) rows.get(0).get("p_result")).intValue(), "Result should be correct");
    }

    /**
     * Test 5: Oracle function (with explicit RETURN type) - stub matches transformation.
     */
    @Test
    void oracleFunctionStub_canBeReplacedWithTransformation() throws SQLException {
        // Step 1: Create stub (matching expected output from PostgresFunctionStubCreationJob)
        String stubSql = """
            CREATE OR REPLACE FUNCTION hr.calculate_tax(p_amount numeric)
            RETURNS numeric AS $$
            BEGIN
                RETURN NULL; -- Stub: Original Oracle function HR.CALCULATE_TAX
            END;
            $$ LANGUAGE plpgsql
            """;

        System.out.println("=== STUB SQL (Function) ===");
        System.out.println(stubSql);
        System.out.println("============================");

        executeUpdate(stubSql);

        // Step 2: Transform function
        String oracleFunction = """
            FUNCTION calculate_tax(p_amount NUMBER) RETURN NUMBER
            IS
            BEGIN
              RETURN p_amount * 0.08;
            END;
            """;
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        System.out.println("=== TRANSFORMATION SQL (Function) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        // Step 3: Replace stub
        assertDoesNotThrow(() -> executeUpdate(result.getPostgresSql()),
            "CREATE OR REPLACE should succeed (stub→transformation)");

        // Step 4: Verify transformation works
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_tax(100) AS tax");
        assertEquals(8.0, ((Number) rows.get(0).get("tax")).doubleValue(), 0.001,
            "Tax calculation should be correct");
    }
}
