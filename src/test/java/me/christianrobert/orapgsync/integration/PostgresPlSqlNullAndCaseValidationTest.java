package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL NULL and CASE statements.
 *
 * <p>Tests the transformation of Oracle NULL statements and CASE statements (procedural)
 * to PostgreSQL equivalents. Syntax is identical in both Oracle and PostgreSQL.
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with NULL and CASE statements</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all scenarios</li>
 *   <li>Validates: syntax correctness, control flow, result correctness</li>
 * </ul>
 *
 * <p>Scope:
 * <ul>
 *   <li>✅ NULL statement - No-op placeholder</li>
 *   <li>✅ Simple CASE statement - CASE expr WHEN value THEN statements</li>
 *   <li>✅ Searched CASE statement - CASE WHEN condition THEN statements</li>
 *   <li>✅ CASE with ELSE clause</li>
 *   <li>✅ CASE without ELSE clause</li>
 *   <li>✅ Nested CASE statements</li>
 * </ul>
 *
 * <h3>Important Distinction:</h3>
 * <ul>
 *   <li>CASE statement (procedural): WHEN/THEN have statements (this test)</li>
 *   <li>CASE expression (SQL): WHEN/THEN have expressions (already tested in CaseExpressionTransformationTest)</li>
 * </ul>
 */
public class PostgresPlSqlNullAndCaseValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    // ==================== NULL Statement Tests ====================

    /**
     * Test 1: NULL statement in IF/ELSE block.
     *
     * <p>Validates:
     * <ul>
     *   <li>NULL statement as placeholder in ELSE clause</li>
     *   <li>Explicit no-op behavior</li>
     *   <li>Clarity for intentionally empty branches</li>
     * </ul>
     */
    @Test
    void nullStatement_inIfElse() throws SQLException {
        // Oracle function with NULL statement
        String oracleFunction = """
            FUNCTION process_value(p_value NUMBER) RETURN NUMBER IS
              v_result NUMBER;
            BEGIN
              IF p_value > 0 THEN
                v_result := p_value * 2;
              ELSE
                NULL;
              END IF;
              RETURN v_result;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed NULL Statement in IF/ELSE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=============================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.process_value(5) AS result");
        double val = ((Number) rows.get(0).get("result")).doubleValue();
        assertEquals(10.0, val, 0.01, "Should double positive value");

        // Negative value → NULL branch executed, result remains uninitialized (NULL in PostgreSQL)
        rows = executeQuery("SELECT hr.process_value(-5) AS result");
        Object negResult = rows.get(0).get("result");
        assertNull(negResult, "Should return NULL when ELSE NULL is executed");
    }

    /**
     * Test 2: Multiple NULL statements.
     *
     * <p>Validates:
     * <ul>
     *   <li>NULL statement in multiple branches</li>
     *   <li>Placeholder for future implementation</li>
     * </ul>
     */
    @Test
    void nullStatement_multipleBranches() throws SQLException {
        // Oracle function with multiple NULL statements
        String oracleFunction = """
            FUNCTION categorize(p_status VARCHAR2) RETURN NUMBER IS
            BEGIN
              IF p_status = 'ACTIVE' THEN
                RETURN 1;
              ELSIF p_status = 'PENDING' THEN
                NULL;
              ELSIF p_status = 'ARCHIVED' THEN
                NULL;
              ELSE
                NULL;
              END IF;
              RETURN 0;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Multiple NULL Statements ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.categorize('ACTIVE') AS result");
        int result_val = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(1, result_val, "ACTIVE should return 1");

        rows = executeQuery("SELECT hr.categorize('PENDING') AS result");
        result_val = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(0, result_val, "PENDING NULL branch should fall through to return 0");
    }

    // ==================== Simple CASE Statement Tests ====================

    /**
     * Test 3: Simple CASE statement with assignment.
     *
     * <p>Validates:
     * <ul>
     *   <li>CASE expr WHEN value THEN statement syntax</li>
     *   <li>Assignment statements in THEN clauses</li>
     *   <li>ELSE clause with default value</li>
     * </ul>
     */
    @Test
    void simpleCaseStatement_gradeEvaluation() throws SQLException {
        // Oracle function with simple CASE statement
        String oracleFunction = """
            FUNCTION evaluate_grade(p_grade VARCHAR2) RETURN VARCHAR2 IS
              v_result VARCHAR2(20);
            BEGIN
              CASE p_grade
                WHEN 'A' THEN v_result := 'Excellent';
                WHEN 'B' THEN v_result := 'Good';
                WHEN 'C' THEN v_result := 'Fair';
                ELSE v_result := 'Unknown';
              END CASE;
              RETURN v_result;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple CASE Statement ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.evaluate_grade('A') AS result");
        assertEquals("Excellent", rows.get(0).get("result"), "Grade A should be Excellent");

        rows = executeQuery("SELECT hr.evaluate_grade('B') AS result");
        assertEquals("Good", rows.get(0).get("result"), "Grade B should be Good");

        rows = executeQuery("SELECT hr.evaluate_grade('D') AS result");
        assertEquals("Unknown", rows.get(0).get("result"), "Grade D should be Unknown");
    }

    /**
     * Test 4: Simple CASE statement with numeric values.
     *
     * <p>Validates:
     * <ul>
     *   <li>Numeric selector expression</li>
     *   <li>Numeric WHEN values</li>
     *   <li>Multiple statements in THEN clause</li>
     * </ul>
     */
    @Test
    void simpleCaseStatement_numericBonus() throws SQLException {
        // Oracle function with numeric simple CASE
        String oracleFunction = """
            FUNCTION calculate_bonus(p_dept_id NUMBER, p_salary NUMBER) RETURN NUMBER IS
              v_rate NUMBER;
              v_bonus NUMBER;
            BEGIN
              CASE p_dept_id
                WHEN 10 THEN v_rate := 0.15;
                WHEN 20 THEN v_rate := 0.10;
                WHEN 30 THEN v_rate := 0.08;
                ELSE v_rate := 0.05;
              END CASE;
              v_bonus := p_salary * v_rate;
              RETURN v_bonus;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple CASE with Numeric ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_bonus(10, 1000) AS bonus");
        double bonus = ((Number) rows.get(0).get("bonus")).doubleValue();
        assertEquals(150.0, bonus, 0.01, "Dept 10 should get 15% bonus");

        rows = executeQuery("SELECT hr.calculate_bonus(20, 1000) AS bonus");
        bonus = ((Number) rows.get(0).get("bonus")).doubleValue();
        assertEquals(100.0, bonus, 0.01, "Dept 20 should get 10% bonus");

        rows = executeQuery("SELECT hr.calculate_bonus(99, 1000) AS bonus");
        bonus = ((Number) rows.get(0).get("bonus")).doubleValue();
        assertEquals(50.0, bonus, 0.01, "Other depts should get 5% bonus");
    }

    // ==================== Searched CASE Statement Tests ====================

    /**
     * Test 5: Searched CASE statement with conditions.
     *
     * <p>Validates:
     * <ul>
     *   <li>CASE WHEN condition THEN statement syntax</li>
     *   <li>Complex conditions with comparisons</li>
     *   <li>Sequential evaluation of WHEN clauses</li>
     * </ul>
     */
    @Test
    void searchedCaseStatement_salaryCategory() throws SQLException {
        // Oracle function with searched CASE statement
        String oracleFunction = """
            FUNCTION categorize_salary(p_salary NUMBER) RETURN VARCHAR2 IS
              v_category VARCHAR2(20);
            BEGIN
              CASE
                WHEN p_salary > 10000 THEN v_category := 'Executive';
                WHEN p_salary > 5000 THEN v_category := 'Senior';
                WHEN p_salary > 2000 THEN v_category := 'Mid-level';
                ELSE v_category := 'Entry-level';
              END CASE;
              RETURN v_category;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Searched CASE Statement ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.categorize_salary(15000) AS category");
        assertEquals("Executive", rows.get(0).get("category"), "High salary should be Executive");

        rows = executeQuery("SELECT hr.categorize_salary(7000) AS category");
        assertEquals("Senior", rows.get(0).get("category"), "Mid-high salary should be Senior");

        rows = executeQuery("SELECT hr.categorize_salary(3000) AS category");
        assertEquals("Mid-level", rows.get(0).get("category"), "Medium salary should be Mid-level");

        rows = executeQuery("SELECT hr.categorize_salary(1500) AS category");
        assertEquals("Entry-level", rows.get(0).get("category"), "Low salary should be Entry-level");
    }

    /**
     * Test 6: Searched CASE with complex conditions.
     *
     * <p>Validates:
     * <ul>
     *   <li>AND/OR conditions in WHEN clauses</li>
     *   <li>Multiple predicates</li>
     *   <li>Conditional branching logic</li>
     * </ul>
     */
    @Test
    void searchedCaseStatement_complexConditions() throws SQLException {
        // Oracle function with complex conditions
        String oracleFunction = """
            FUNCTION evaluate_employee(p_salary NUMBER, p_years NUMBER) RETURN VARCHAR2 IS
              v_status VARCHAR2(20);
            BEGIN
              CASE
                WHEN p_salary > 8000 AND p_years > 5 THEN v_status := 'Senior High';
                WHEN p_salary > 5000 OR p_years > 10 THEN v_status := 'Experienced';
                WHEN p_salary > 2000 THEN v_status := 'Regular';
                ELSE v_status := 'Junior';
              END CASE;
              RETURN v_status;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed CASE with Complex Conditions ===");
        System.out.println(result.getPostgresSql());
        System.out.println("================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.evaluate_employee(9000, 6) AS status");
        assertEquals("Senior High", rows.get(0).get("status"), "High salary and years should be Senior High");

        rows = executeQuery("SELECT hr.evaluate_employee(6000, 3) AS status");
        assertEquals("Experienced", rows.get(0).get("status"), "Mid salary OR high years should be Experienced");

        rows = executeQuery("SELECT hr.evaluate_employee(3000, 2) AS status");
        assertEquals("Regular", rows.get(0).get("status"), "Regular salary should be Regular");
    }

    /**
     * Test 7: CASE statement without ELSE clause (with guaranteed match).
     *
     * <p>Validates:
     * <ul>
     *   <li>CASE statement with no ELSE (optional in Oracle)</li>
     *   <li>Note: PostgreSQL requires at least one WHEN to match at runtime</li>
     *   <li>Test ensures one WHEN always matches</li>
     * </ul>
     *
     * <p><strong>Important PostgreSQL difference:</strong>
     * Oracle allows CASE without ELSE and variable remains unchanged if no match.
     * PostgreSQL raises runtime error "case not found" if no WHEN matches and no ELSE exists.
     * This test uses logic that guarantees a match to demonstrate syntax support.
     */
    @Test
    void caseStatement_withoutElse() throws SQLException {
        // Oracle function with CASE without ELSE (but logic ensures match)
        String oracleFunction = """
            FUNCTION apply_discount(p_category VARCHAR2, p_price NUMBER) RETURN NUMBER IS
              v_discount_rate NUMBER := 0;
            BEGIN
              CASE p_category
                WHEN 'VIP' THEN v_discount_rate := 0.20;
                WHEN 'MEMBER' THEN v_discount_rate := 0.10;
                WHEN 'REGULAR' THEN v_discount_rate := 0.0;
              END CASE;
              RETURN p_price * (1 - v_discount_rate);
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed CASE without ELSE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.apply_discount('VIP', 100) AS final_price");
        double price = ((Number) rows.get(0).get("final_price")).doubleValue();
        assertEquals(80.0, price, 0.01, "VIP should get 20% discount");

        rows = executeQuery("SELECT hr.apply_discount('REGULAR', 100) AS final_price");
        price = ((Number) rows.get(0).get("final_price")).doubleValue();
        assertEquals(100.0, price, 0.01, "REGULAR should get no discount (explicit 0.0 rate)");
    }

    /**
     * Test 8: Nested CASE statements.
     *
     * <p>Validates:
     * <ul>
     *   <li>CASE statement inside another CASE</li>
     *   <li>Complex nested conditional logic</li>
     *   <li>Proper scoping and execution</li>
     * </ul>
     */
    @Test
    void caseStatement_nested() throws SQLException {
        // Oracle function with nested CASE statements
        String oracleFunction = """
            FUNCTION calculate_tax(p_income NUMBER, p_state VARCHAR2) RETURN NUMBER IS
              v_rate NUMBER;
            BEGIN
              CASE p_state
                WHEN 'CA' THEN
                  CASE
                    WHEN p_income > 50000 THEN v_rate := 0.10;
                    ELSE v_rate := 0.08;
                  END CASE;
                WHEN 'TX' THEN
                  v_rate := 0.06;
                ELSE
                  v_rate := 0.05;
              END CASE;
              RETURN p_income * v_rate;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Nested CASE Statements ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.calculate_tax(60000, 'CA') AS tax");
        double tax = ((Number) rows.get(0).get("tax")).doubleValue();
        assertEquals(6000.0, tax, 0.01, "CA high income should get 10% tax");

        rows = executeQuery("SELECT hr.calculate_tax(40000, 'CA') AS tax");
        tax = ((Number) rows.get(0).get("tax")).doubleValue();
        assertEquals(3200.0, tax, 0.01, "CA low income should get 8% tax");

        rows = executeQuery("SELECT hr.calculate_tax(50000, 'TX') AS tax");
        tax = ((Number) rows.get(0).get("tax")).doubleValue();
        assertEquals(3000.0, tax, 0.01, "TX should get 6% tax");
    }
}
