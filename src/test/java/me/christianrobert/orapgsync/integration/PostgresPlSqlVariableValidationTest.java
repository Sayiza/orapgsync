package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL variable declarations.
 *
 * <p>Tests the transformation of Oracle variable declarations to PostgreSQL equivalents.
 * Follows comprehensive testing philosophy: each test validates multiple aspects together
 * (parsing, transformation, execution, result correctness).
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with variable declarations</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions and verify results</li>
 *   <li>Validates: syntax correctness, type conversion, default values, execution</li>
 * </ul>
 */
public class PostgresPlSqlVariableValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema for functions
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Simple variable declarations with primitive types.
     *
     * <p>Validates:
     * <ul>
     *   <li>NUMBER → numeric type conversion</li>
     *   <li>VARCHAR2 → text type conversion</li>
     *   <li>Variable declarations without default values</li>
     *   <li>Variable assignments using :=</li>
     *   <li>Function execution and result correctness</li>
     * </ul>
     */
    @Test
    void simpleVariableDeclarations_primitiveTypes() throws SQLException {
        // Oracle function body (without CREATE OR REPLACE prefix)
        String oracleFunction = """
            FUNCTION calculate_discount(p_price NUMBER) RETURN NUMBER IS
              v_discount NUMBER;
              v_message VARCHAR2(100);
            BEGIN
              v_discount := p_price * 0.10;
              v_message := 'Discount applied';
              RETURN v_discount;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== Transformed Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Call function and verify result
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_discount(100) AS discount");
        assertEquals(1, rows.size(), "Should return exactly one row");

        Object discountValue = rows.get(0).get("discount");
        assertNotNull(discountValue, "Discount value should not be null");

        // Verify result (10% of 100 = 10.00)
        double discount = ((Number) discountValue).doubleValue();
        assertEquals(10.0, discount, 0.001, "Discount should be 10.0");
    }

    /**
     * Test 2: Variable declarations with default values.
     *
     * <p>Validates:
     * <ul>
     *   <li>Default value assignment with :=</li>
     *   <li>Default value expressions (arithmetic)</li>
     *   <li>Variables initialized at declaration</li>
     *   <li>Function execution using default values</li>
     * </ul>
     */
    @Test
    void variableDeclarationsWithDefaults_correctInitialization() throws SQLException {
        // Oracle function body with default values
        String oracleFunction = """
            FUNCTION calculate_tax(p_amount NUMBER) RETURN NUMBER IS
              v_tax_rate NUMBER := 0.08;
              v_tax NUMBER;
            BEGIN
              v_tax := p_amount * v_tax_rate;
              RETURN v_tax;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Function with Defaults ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        // Verify transformation
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute function
        executeUpdate(result.getPostgresSql());

        // Test with multiple input values
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.calculate_tax(100) AS tax");
        double tax1 = ((Number) rows1.get(0).get("tax")).doubleValue();
        assertEquals(8.0, tax1, 0.001, "Tax for 100 should be 8.0");

        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.calculate_tax(50) AS tax");
        double tax2 = ((Number) rows2.get(0).get("tax")).doubleValue();
        assertEquals(4.0, tax2, 0.001, "Tax for 50 should be 4.0");
    }

    /**
     * Test 3: Multiple variable types in one function.
     *
     * <p>Validates:
     * <ul>
     *   <li>NUMBER, VARCHAR2, DATE type conversions</li>
     *   <li>Multiple declarations in DECLARE section</li>
     *   <li>Type-specific operations</li>
     *   <li>Complex expressions in assignments</li>
     * </ul>
     */
    @Test
    void multipleVariableTypes_allConvertCorrectly() throws SQLException {
        // Oracle function body with multiple types
        String oracleFunction = """
            FUNCTION process_order(p_quantity NUMBER) RETURN NUMBER IS
              v_price NUMBER := 10.50;
              v_status VARCHAR2(50);
              v_total NUMBER;
            BEGIN
              v_total := p_quantity * v_price;
              v_status := 'PROCESSED';
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Multi-Type Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Verify calculation
        List<Map<String, Object>> rows = executeQuery("SELECT hr.process_order(5) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(52.5, total, 0.001, "Total for 5 items at 10.50 each should be 52.5");
    }

    /**
     * Test 4: CONSTANT variables.
     *
     * <p>Validates:
     * <ul>
     *   <li>CONSTANT keyword preservation</li>
     *   <li>Constant variables with required initialization</li>
     *   <li>PostgreSQL CONSTANT semantics match Oracle</li>
     * </ul>
     */
    @Test
    void constantVariables_preservedCorrectly() throws SQLException {
        // Oracle function body with CONSTANT variable
        String oracleFunction = """
            FUNCTION apply_markup(p_cost NUMBER) RETURN NUMBER IS
              v_markup_rate CONSTANT NUMBER := 0.25;
            BEGIN
              RETURN p_cost * (1 + v_markup_rate);
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed CONSTANT Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify CONSTANT keyword is in generated SQL
        assertTrue(result.getPostgresSql().toUpperCase().contains("CONSTANT"),
                "Generated SQL should contain CONSTANT keyword");

        executeUpdate(result.getPostgresSql());

        // Verify result (cost * 1.25)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.apply_markup(100) AS price");
        double price = ((Number) rows.get(0).get("price")).doubleValue();
        assertEquals(125.0, price, 0.001, "Markup price should be 125.0");
    }

    /**
     * Test 5: NOT NULL constraints on variables.
     *
     * <p>Validates:
     * <ul>
     *   <li>NOT NULL constraint preservation</li>
     *   <li>NOT NULL variables require initialization</li>
     *   <li>PostgreSQL enforces NOT NULL semantics</li>
     * </ul>
     */
    @Test
    void notNullVariables_preservedCorrectly() throws SQLException {
        // Oracle function body with NOT NULL variable
        String oracleFunction = """
            FUNCTION get_default_rate RETURN NUMBER IS
              v_rate NUMBER NOT NULL := 0.05;
            BEGIN
              RETURN v_rate;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed NOT NULL Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Verify NOT NULL is in generated SQL
        assertTrue(result.getPostgresSql().toUpperCase().contains("NOT NULL"),
                "Generated SQL should contain NOT NULL constraint");

        executeUpdate(result.getPostgresSql());

        // Verify result
        List<Map<String, Object>> rows = executeQuery("SELECT hr.get_default_rate() AS rate");
        double rate = ((Number) rows.get(0).get("rate")).doubleValue();
        assertEquals(0.05, rate, 0.001, "Default rate should be 0.05");
    }
}
