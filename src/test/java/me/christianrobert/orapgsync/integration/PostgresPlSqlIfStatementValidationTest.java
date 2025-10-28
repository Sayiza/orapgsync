package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL IF/ELSIF/ELSE statements.
 *
 * <p>Tests the transformation of Oracle IF statements to PostgreSQL equivalents.
 * Follows comprehensive testing philosophy: each test validates multiple aspects together
 * (parsing, transformation, execution, result correctness).
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with IF statements</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all branches</li>
 *   <li>Validates: syntax correctness, condition evaluation, branch selection, result correctness</li>
 * </ul>
 */
public class PostgresPlSqlIfStatementValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema for functions
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Simple IF/ELSE with condition evaluation.
     *
     * <p>Validates:
     * <ul>
     *   <li>Basic IF/ELSE structure</li>
     *   <li>Condition transformation (comparison operators)</li>
     *   <li>Variable assignments in branches</li>
     *   <li>Different results based on condition</li>
     * </ul>
     */
    @Test
    void simpleIfElse_correctBranchSelection() throws SQLException {
        // Oracle function with simple IF/ELSE
        String oracleFunction = """
            FUNCTION get_status(p_value NUMBER) RETURN VARCHAR2 IS
              v_status VARCHAR2(20);
            BEGIN
              IF p_value > 100 THEN
                v_status := 'HIGH';
              ELSE
                v_status := 'LOW';
              END IF;
              RETURN v_status;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple IF/ELSE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test TRUE branch (p_value > 100)
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.get_status(150) AS status");
        assertEquals("HIGH", rows1.get(0).get("status"), "Value 150 should return HIGH");

        // Test FALSE branch (p_value <= 100)
        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.get_status(50) AS status");
        assertEquals("LOW", rows2.get(0).get("status"), "Value 50 should return LOW");

        // Test boundary condition
        List<Map<String, Object>> rows3 = executeQuery("SELECT hr.get_status(100) AS status");
        assertEquals("LOW", rows3.get(0).get("status"), "Value 100 should return LOW (not > 100)");
    }

    /**
     * Test 2: IF/ELSIF/ELSE chain with multiple conditions.
     *
     * <p>Validates:
     * <ul>
     *   <li>ELSIF keyword (not ELSEIF)</li>
     *   <li>Multiple ELSIF branches</li>
     *   <li>Correct evaluation order (top to bottom)</li>
     *   <li>Final ELSE as fallback</li>
     * </ul>
     */
    @Test
    void ifElsifElseChain_multipleConditions() throws SQLException {
        // Oracle function with IF/ELSIF/ELSE chain
        String oracleFunction = """
            FUNCTION categorize_score(p_score NUMBER) RETURN VARCHAR2 IS
              v_grade VARCHAR2(10);
            BEGIN
              IF p_score >= 90 THEN
                v_grade := 'A';
              ELSIF p_score >= 80 THEN
                v_grade := 'B';
              ELSIF p_score >= 70 THEN
                v_grade := 'C';
              ELSIF p_score >= 60 THEN
                v_grade := 'D';
              ELSE
                v_grade := 'F';
              END IF;
              RETURN v_grade;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed IF/ELSIF/ELSE Chain ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test each branch
        assertEquals("A", executeQuery("SELECT hr.categorize_score(95) AS grade").get(0).get("grade"));
        assertEquals("B", executeQuery("SELECT hr.categorize_score(85) AS grade").get(0).get("grade"));
        assertEquals("C", executeQuery("SELECT hr.categorize_score(75) AS grade").get(0).get("grade"));
        assertEquals("D", executeQuery("SELECT hr.categorize_score(65) AS grade").get(0).get("grade"));
        assertEquals("F", executeQuery("SELECT hr.categorize_score(50) AS grade").get(0).get("grade"));

        // Test boundary conditions
        assertEquals("A", executeQuery("SELECT hr.categorize_score(90) AS grade").get(0).get("grade"), "90 should be A");
        assertEquals("B", executeQuery("SELECT hr.categorize_score(80) AS grade").get(0).get("grade"), "80 should be B");
        assertEquals("F", executeQuery("SELECT hr.categorize_score(59) AS grade").get(0).get("grade"), "59 should be F");
    }

    /**
     * Test 3: Nested IF statements.
     *
     * <p>Validates:
     * <ul>
     *   <li>IF statements inside IF branches</li>
     *   <li>Proper indentation and structure preservation</li>
     *   <li>Multiple levels of nesting</li>
     *   <li>Complex condition logic</li>
     * </ul>
     */
    @Test
    void nestedIfStatements_correctNesting() throws SQLException {
        // Oracle function with nested IF
        String oracleFunction = """
            FUNCTION get_discount(p_amount NUMBER, p_vip NUMBER) RETURN NUMBER IS
              v_discount NUMBER;
            BEGIN
              IF p_amount > 1000 THEN
                IF p_vip = 1 THEN
                  v_discount := 0.20;
                ELSE
                  v_discount := 0.10;
                END IF;
              ELSE
                IF p_vip = 1 THEN
                  v_discount := 0.05;
                ELSE
                  v_discount := 0.0;
                END IF;
              END IF;
              RETURN v_discount;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Nested IF ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=============================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test all four combinations
        double discount1 = ((Number) executeQuery("SELECT hr.get_discount(2000, 1) AS discount")
                .get(0).get("discount")).doubleValue();
        assertEquals(0.20, discount1, 0.001, "High amount + VIP = 20%");

        double discount2 = ((Number) executeQuery("SELECT hr.get_discount(2000, 0) AS discount")
                .get(0).get("discount")).doubleValue();
        assertEquals(0.10, discount2, 0.001, "High amount + non-VIP = 10%");

        double discount3 = ((Number) executeQuery("SELECT hr.get_discount(500, 1) AS discount")
                .get(0).get("discount")).doubleValue();
        assertEquals(0.05, discount3, 0.001, "Low amount + VIP = 5%");

        double discount4 = ((Number) executeQuery("SELECT hr.get_discount(500, 0) AS discount")
                .get(0).get("discount")).doubleValue();
        assertEquals(0.0, discount4, 0.001, "Low amount + non-VIP = 0%");
    }

    /**
     * Test 4: IF with only THEN branch (no ELSE).
     *
     * <p>Validates:
     * <ul>
     *   <li>Optional ELSE clause</li>
     *   <li>Variable initialization before IF</li>
     *   <li>Conditional modification of variables</li>
     * </ul>
     */
    @Test
    void ifWithoutElse_optionalElseClause() throws SQLException {
        // Oracle function with IF but no ELSE
        String oracleFunction = """
            FUNCTION apply_bonus(p_salary NUMBER, p_performance NUMBER) RETURN NUMBER IS
              v_result NUMBER;
            BEGIN
              v_result := p_salary;
              IF p_performance > 8 THEN
                v_result := v_result * 1.10;
              END IF;
              RETURN v_result;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed IF without ELSE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test with condition TRUE (should apply 10% bonus)
        double result1 = ((Number) executeQuery("SELECT hr.apply_bonus(1000, 9) AS salary")
                .get(0).get("salary")).doubleValue();
        assertEquals(1100.0, result1, 0.01, "Performance 9 should give 10% bonus");

        // Test with condition FALSE (should return original salary)
        double result2 = ((Number) executeQuery("SELECT hr.apply_bonus(1000, 7) AS salary")
                .get(0).get("salary")).doubleValue();
        assertEquals(1000.0, result2, 0.01, "Performance 7 should give no bonus");
    }

    /**
     * Test 5: IF with complex conditions (AND/OR logic).
     *
     * <p>Validates:
     * <ul>
     *   <li>Complex boolean expressions in conditions</li>
     *   <li>AND/OR operators</li>
     *   <li>Parentheses in conditions</li>
     *   <li>Multiple comparison operators</li>
     * </ul>
     */
    @Test
    void ifWithComplexConditions_logicalOperators() throws SQLException {
        // Oracle function with complex conditions
        String oracleFunction = """
            FUNCTION check_eligibility(p_age NUMBER, p_income NUMBER, p_credit NUMBER) RETURN VARCHAR2 IS
              v_eligible VARCHAR2(10);
            BEGIN
              IF (p_age >= 18 AND p_age <= 65) AND (p_income > 30000 OR p_credit > 700) THEN
                v_eligible := 'YES';
              ELSE
                v_eligible := 'NO';
              END IF;
              RETURN v_eligible;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Complex Conditions ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test various combinations
        assertEquals("YES", executeQuery("SELECT hr.check_eligibility(25, 40000, 650) AS eligible")
                .get(0).get("eligible"), "Age OK + High income = YES");

        assertEquals("YES", executeQuery("SELECT hr.check_eligibility(30, 25000, 750) AS eligible")
                .get(0).get("eligible"), "Age OK + Good credit = YES");

        assertEquals("NO", executeQuery("SELECT hr.check_eligibility(17, 40000, 800) AS eligible")
                .get(0).get("eligible"), "Age too low = NO");

        assertEquals("NO", executeQuery("SELECT hr.check_eligibility(70, 40000, 800) AS eligible")
                .get(0).get("eligible"), "Age too high = NO");

        assertEquals("NO", executeQuery("SELECT hr.check_eligibility(25, 20000, 650) AS eligible")
                .get(0).get("eligible"), "Age OK but low income and credit = NO");
    }
}
