package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL OUT parameters in procedures.
 *
 * <p>Tests the transformation of Oracle procedures with OUT parameters to PostgreSQL functions.
 * Validates that OUT parameters are correctly included in the signature and RETURNS clause
 * is calculated correctly.
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle procedures with OUT parameters</li>
 *   <li>Verify RETURNS clause is correct (void, type, or RECORD)</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions and verify OUT parameter values</li>
 * </ul>
 */
public class PostgresPlSqlOutParameterValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema for functions
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Procedure with single OUT parameter.
     *
     * <p>Validates:
     * <ul>
     *   <li>OUT parameter included in signature with OUT keyword</li>
     *   <li>RETURNS clause is the type of the OUT parameter (not void)</li>
     *   <li>IN parameters come before OUT parameters</li>
     *   <li>Function execution returns OUT parameter value</li>
     * </ul>
     */
    @Test
    void procedureWithSingleOutParameter_returnsType() throws SQLException {
        // Oracle procedure body (without CREATE OR REPLACE prefix)
        String oracleProcedure = """
            PROCEDURE divide_numbers
              (p_dividend IN number,
               p_divisor IN number,
               p_quotient OUT number)
            IS
            BEGIN
              p_quotient := TRUNC(p_dividend / p_divisor);
            END divide_numbers;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== Transformed Procedure (Single OUT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify signature contains OUT parameter
        String pgSql = result.getPostgresSql();
        assertTrue(pgSql.contains("p_quotient OUT numeric"),
            "Signature should include OUT parameter");
        assertTrue(pgSql.contains("RETURNS numeric"),
            "Should RETURN numeric (type of single OUT param), not void");

        // Execute transformed procedure in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Call procedure and verify result (100 / 3 = 33)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.divide_numbers(100, 3) AS quotient");
        assertEquals(1, rows.size(), "Should return exactly one row");

        Object quotientValue = rows.get(0).get("quotient");
        assertNotNull(quotientValue, "Quotient value should not be null");

        // Verify result
        double quotient = ((Number) quotientValue).doubleValue();
        assertEquals(33.0, quotient, 0.001, "Quotient should be 33 (100/3 truncated)");
    }

    /**
     * Test 2: Procedure with multiple OUT parameters.
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple OUT parameters included in signature</li>
     *   <li>RETURNS RECORD for multiple OUT parameters</li>
     *   <li>Function execution returns RECORD with all OUT values</li>
     * </ul>
     */
    @Test
    void procedureWithMultipleOutParameters_returnsRecord() throws SQLException {
        // Oracle procedure body
        String oracleProcedure = """
            PROCEDURE calculate_stats
              (p_value1 IN number,
               p_value2 IN number,
               p_sum OUT number,
               p_product OUT number)
            IS
            BEGIN
              p_sum := p_value1 + p_value2;
              p_product := p_value1 * p_value2;
            END calculate_stats;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== Transformed Procedure (Multiple OUT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify signature contains both OUT parameters
        String pgSql = result.getPostgresSql();
        assertTrue(pgSql.contains("p_sum OUT numeric"),
            "Signature should include first OUT parameter");
        assertTrue(pgSql.contains("p_product OUT numeric"),
            "Signature should include second OUT parameter");
        assertTrue(pgSql.contains("RETURNS RECORD"),
            "Should RETURN RECORD for multiple OUT parameters");

        // Execute transformed procedure in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Call procedure and verify results (5 + 10 = 15, 5 * 10 = 50)
        // Note: When calling functions with OUT parameters, column list is not needed
        List<Map<String, Object>> rows = executeQuery(
            "SELECT * FROM hr.calculate_stats(5, 10)"
        );
        assertEquals(1, rows.size(), "Should return exactly one row");

        Object sumValue = rows.get(0).get("p_sum");
        Object productValue = rows.get(0).get("p_product");
        assertNotNull(sumValue, "Sum value should not be null");
        assertNotNull(productValue, "Product value should not be null");

        // Verify results
        double sum = ((Number) sumValue).doubleValue();
        double product = ((Number) productValue).doubleValue();
        assertEquals(15.0, sum, 0.001, "Sum should be 15");
        assertEquals(50.0, product, 0.001, "Product should be 50");
    }

    /**
     * Test 3: Procedure with no OUT parameters (pure procedure).
     *
     * <p>Validates:
     * <ul>
     *   <li>RETURNS void when no OUT parameters</li>
     *   <li>Only IN parameters in signature</li>
     *   <li>Procedure executes successfully</li>
     * </ul>
     *
     * <p>Note: Currently commented out because INSERT statements are not yet supported
     * in PL/SQL transformation. This test will be enabled once INSERT support is added.
     */
    // @Test
    void procedureWithNoOutParameters_returnsVoid_DISABLED() throws SQLException {
        // Create a table to verify procedure side effects
        executeUpdate("CREATE TABLE hr.logs (message text)");

        // Oracle procedure body (side effects only, no OUT parameters)
        String oracleProcedure = """
            PROCEDURE log_message
              (p_message IN VARCHAR2)
            IS
            BEGIN
              INSERT INTO logs (message) VALUES (p_message);
            END log_message;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== Transformed Procedure (No OUT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify signature
        String pgSql = result.getPostgresSql();
        assertFalse(pgSql.contains(" OUT "),
            "Signature should not include OUT parameters");
        assertTrue(pgSql.contains("RETURNS void"),
            "Should RETURN void when no OUT parameters");

        // Execute transformed procedure in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Call procedure
        executeUpdate("SELECT hr.log_message('Test message')");

        // Verify side effect
        List<Map<String, Object>> rows = executeQuery("SELECT message FROM hr.logs");
        assertEquals(1, rows.size(), "Should have one log entry");
        assertEquals("Test message", rows.get(0).get("message"),
            "Log message should match");
    }

    /**
     * Test 4: Procedure with mixed IN/OUT/INOUT parameters.
     *
     * <p>Validates:
     * <ul>
     *   <li>Correct handling of IN, OUT, and INOUT parameters</li>
     *   <li>Parameter order preserved</li>
     *   <li>RETURNS RECORD for multiple OUT/INOUT parameters</li>
     * </ul>
     */
    @Test
    void procedureWithMixedParameters_correctParameterModes() throws SQLException {
        // Oracle procedure body
        String oracleProcedure = """
            PROCEDURE adjust_values
              (p_input IN number,
               p_counter INOUT number,
               p_result OUT number)
            IS
            BEGIN
              p_counter := p_counter + 1;
              p_result := p_input * p_counter;
            END adjust_values;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== Transformed Procedure (Mixed Parameters) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("================================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify signature
        String pgSql = result.getPostgresSql();
        assertTrue(pgSql.contains("p_input numeric"),
            "Should have IN parameter (no keyword)");
        assertTrue(pgSql.contains("p_counter INOUT numeric"),
            "Should have INOUT parameter with keyword");
        assertTrue(pgSql.contains("p_result OUT numeric"),
            "Should have OUT parameter with keyword");
        assertTrue(pgSql.contains("RETURNS RECORD"),
            "Should RETURN RECORD for mixed OUT/INOUT parameters");

        // Execute transformed procedure in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Call procedure with initial counter value 5
        // Expected: counter becomes 6, result = 10 * 6 = 60
        // Note: When calling functions with OUT/INOUT parameters, column list is not needed
        List<Map<String, Object>> rows = executeQuery(
            "SELECT * FROM hr.adjust_values(10, 5)"
        );
        assertEquals(1, rows.size(), "Should return exactly one row");

        Object counterValue = rows.get(0).get("p_counter");
        Object resultValue = rows.get(0).get("p_result");
        assertNotNull(counterValue, "Counter value should not be null");
        assertNotNull(resultValue, "Result value should not be null");

        // Verify results
        double counter = ((Number) counterValue).doubleValue();
        double resultVal = ((Number) resultValue).doubleValue();
        assertEquals(6.0, counter, 0.001, "Counter should be incremented to 6");
        assertEquals(60.0, resultVal, 0.001, "Result should be 60 (10 * 6)");
    }

    /**
     * Test 5: Procedure with VARCHAR2 OUT parameter.
     *
     * <p>Validates:
     * <ul>
     *   <li>Type conversion for non-numeric OUT parameters (VARCHAR2 → text)</li>
     *   <li>RETURNS text for single VARCHAR2 OUT parameter</li>
     *   <li>String operations work correctly</li>
     * </ul>
     */
    @Test
    void procedureWithVarchar2OutParameter_returnsText() throws SQLException {
        // Oracle procedure body
        String oracleProcedure = """
            PROCEDURE format_name
              (p_first_name IN VARCHAR2,
               p_last_name IN VARCHAR2,
               p_full_name OUT VARCHAR2)
            IS
            BEGIN
              p_full_name := p_last_name || ', ' || p_first_name;
            END format_name;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformProcedure(oracleProcedure, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== Transformed Procedure (VARCHAR2 OUT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify signature
        String pgSql = result.getPostgresSql();
        assertTrue(pgSql.contains("p_full_name OUT text"),
            "Should have OUT parameter with text type");
        assertTrue(pgSql.contains("RETURNS text"),
            "Should RETURN text for VARCHAR2 OUT parameter");

        // Execute transformed procedure in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Call procedure
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.format_name('John', 'Doe') AS full_name"
        );
        assertEquals(1, rows.size(), "Should return exactly one row");

        String fullName = (String) rows.get(0).get("full_name");
        assertNotNull(fullName, "Full name should not be null");
        assertEquals("Doe, John", fullName, "Full name should be formatted correctly");
    }
}
