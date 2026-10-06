package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for inline RECORD types (Phase 1B).
 *
 * <p>These tests verify that transformed inline RECORD types execute correctly
 * in PostgreSQL using Testcontainers. Each test:
 * <ol>
 *   <li>Transforms Oracle PL/SQL with inline RECORD types</li>
 *   <li>Executes transformed code in real PostgreSQL database</li>
 *   <li>Calls functions and verifies results are correct</li>
 * </ol>
 *
 * <p><b>Phase 1B Scope:</b>
 * <ul>
 *   <li>✅ TYPE declarations (RECORD)</li>
 *   <li>✅ Variable declarations → jsonb conversion</li>
 *   <li>✅ Field assignment (LHS) → jsonb_set transformation</li>
 *   <li>✅ Nested field assignment → jsonb_set with path arrays</li>
 *   <li>❌ RHS field access (deferred to Phase 1B.5)</li>
 * </ul>
 *
 * <p>See: INLINE_TYPE_IMPLEMENTATION_PLAN.md Phase 1B (lines 538-595)
 */
public class PostgresInlineTypeRecordValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema for functions
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Simple RECORD with field assignments.
     *
     * <p>Validates:
     * <ul>
     *   <li>RECORD type declaration and registration</li>
     *   <li>Variable declaration with jsonb conversion</li>
     *   <li>Simple field assignments using jsonb_set</li>
     *   <li>Function executes without errors</li>
     *   <li>Return value is correct</li>
     * </ul>
     *
     * <p><b>Note:</b> Cannot verify field values directly (RHS not implemented yet),
     * so this test verifies the function executes and returns expected constant.
     */
    @Test
    void simpleRecord_fieldAssignments_executesSuccessfully() throws SQLException {
        // Oracle function body with simple RECORD type
        String oracleFunction = """
            FUNCTION calculate_discount(p_price NUMBER) RETURN NUMBER IS
              TYPE discount_t IS RECORD (
                amount NUMBER,
                rate NUMBER,
                description VARCHAR2(100)
              );
              v_discount discount_t;
            BEGIN
              v_discount.amount := p_price * 0.10;
              v_discount.rate := 0.10;
              v_discount.description := 'Standard discount';
              RETURN p_price * 0.10;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug: Print transformed SQL
        System.out.println("=== TEST 1: Simple RECORD with Field Assignments ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================================");

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
     * Test 2: Nested RECORD types with multi-level assignments.
     *
     * <p>Validates:
     * <ul>
     *   <li>Nested RECORD type definitions</li>
     *   <li>Multi-level field access paths (v.address.city)</li>
     *   <li>jsonb_set with path arrays: {address,city}</li>
     *   <li>create_if_missing parameter for nested paths</li>
     *   <li>Function executes with nested jsonb operations</li>
     * </ul>
     */
    @Test
    void nestedRecord_multiLevelAssignments_executesSuccessfully() throws SQLException {
        // Oracle function body with nested RECORD types
        String oracleFunction = """
            FUNCTION process_employee(p_empno NUMBER) RETURN NUMBER IS
              TYPE address_t IS RECORD (
                street VARCHAR2(100),
                city VARCHAR2(50),
                zipcode VARCHAR2(10)
              );
              TYPE employee_t IS RECORD (
                empno NUMBER,
                ename VARCHAR2(50),
                address address_t
              );
              v_emp employee_t;
            BEGIN
              v_emp.empno := p_empno;
              v_emp.ename := 'John Smith';
              v_emp.address.street := '123 Main St';
              v_emp.address.city := 'Boston';
              v_emp.address.zipcode := '02101';
              RETURN p_empno;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== TEST 2: Nested RECORD with Multi-Level Assignments ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Verify function executes and returns correct value
        List<Map<String, Object>> rows = executeQuery("SELECT hr.process_employee(100) AS empno");
        assertEquals(1, rows.size(), "Should return exactly one row");

        Object empnoValue = rows.get(0).get("empno");
        assertNotNull(empnoValue, "Employee number should not be null");

        int empno = ((Number) empnoValue).intValue();
        assertEquals(100, empno, "Should return the input employee number");
    }

    /**
     * Test 3: Multiple RECORD variables in one function.
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple RECORD type definitions</li>
     *   <li>Multiple jsonb variables don't interfere with each other</li>
     *   <li>Field assignments to different variables work correctly</li>
     *   <li>Complex function logic with multiple inline types</li>
     * </ul>
     */
    @Test
    void multipleRecords_independentVariables_executesSuccessfully() throws SQLException {
        // Oracle function body with multiple RECORD types
        String oracleFunction = """
            FUNCTION process_order(p_quantity NUMBER, p_price NUMBER) RETURN NUMBER IS
              TYPE order_info_t IS RECORD (
                quantity NUMBER,
                unit_price NUMBER,
                subtotal NUMBER
              );
              TYPE tax_info_t IS RECORD (
                tax_rate NUMBER,
                tax_amount NUMBER
              );
              v_order order_info_t;
              v_tax tax_info_t;
              v_total NUMBER;
            BEGIN
              v_order.quantity := p_quantity;
              v_order.unit_price := p_price;
              v_order.subtotal := p_quantity * p_price;

              v_tax.tax_rate := 0.08;
              v_tax.tax_amount := (p_quantity * p_price) * 0.08;

              v_total := (p_quantity * p_price) * 1.08;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== TEST 3: Multiple RECORD Variables ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test with sample values: 5 items at $10.00 each = $50.00 + 8% tax = $54.00
        List<Map<String, Object>> rows = executeQuery("SELECT hr.process_order(5, 10.00) AS total");
        assertEquals(1, rows.size(), "Should return exactly one row");

        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(54.0, total, 0.01, "Total should be 54.0 (50.00 + 8% tax)");
    }

    /**
     * Test 4: RECORD in control flow statements (IF/LOOP).
     *
     * <p>Validates:
     * <ul>
     *   <li>RECORD field assignments inside IF blocks</li>
     *   <li>RECORD field assignments inside LOOP blocks</li>
     *   <li>Conditional logic with RECORD variables</li>
     *   <li>Complex control flow with inline types</li>
     * </ul>
     */
    @Test
    void recordInControlFlow_ifAndLoop_executesSuccessfully() throws SQLException {
        // Oracle function body with RECORD in IF and LOOP
        String oracleFunction = """
            FUNCTION calculate_bonus(p_salary NUMBER, p_years NUMBER) RETURN NUMBER IS
              TYPE bonus_t IS RECORD (
                rate NUMBER,
                amount NUMBER,
                description VARCHAR2(100)
              );
              v_bonus bonus_t;
              i NUMBER;
              v_total NUMBER := 0;
            BEGIN
              -- Use RECORD in IF statement
              IF p_years >= 5 THEN
                v_bonus.rate := 0.15;
                v_bonus.description := 'Senior bonus';
              ELSIF p_years >= 2 THEN
                v_bonus.rate := 0.10;
                v_bonus.description := 'Standard bonus';
              ELSE
                v_bonus.rate := 0.05;
                v_bonus.description := 'Entry bonus';
              END IF;

              -- Use RECORD in LOOP
              FOR i IN 1..p_years LOOP
                v_bonus.amount := p_salary * v_bonus.rate;
                v_total := v_total + v_bonus.amount;
              END LOOP;

              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== TEST 4: RECORD in Control Flow (IF/LOOP) ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test with different scenarios

        // Scenario 1: 5 years (15% rate) - bonus per year = 50000 * 0.15 = 7500, total = 7500 * 5 = 37500
        List<Map<String, Object>> rows1 = executeQuery("SELECT hr.calculate_bonus(50000, 5) AS bonus");
        double bonus1 = ((Number) rows1.get(0).get("bonus")).doubleValue();
        assertEquals(37500.0, bonus1, 0.01, "Bonus for 5 years should be 37500.0");

        // Scenario 2: 3 years (10% rate) - bonus per year = 50000 * 0.10 = 5000, total = 5000 * 3 = 15000
        List<Map<String, Object>> rows2 = executeQuery("SELECT hr.calculate_bonus(50000, 3) AS bonus");
        double bonus2 = ((Number) rows2.get(0).get("bonus")).doubleValue();
        assertEquals(15000.0, bonus2, 0.01, "Bonus for 3 years should be 15000.0");

        // Scenario 3: 1 year (5% rate) - bonus per year = 50000 * 0.05 = 2500, total = 2500 * 1 = 2500
        List<Map<String, Object>> rows3 = executeQuery("SELECT hr.calculate_bonus(50000, 1) AS bonus");
        double bonus3 = ((Number) rows3.get(0).get("bonus")).doubleValue();
        assertEquals(2500.0, bonus3, 0.01, "Bonus for 1 year should be 2500.0");
    }

    /**
     * Test 5: RECORD with various Oracle data types.
     *
     * <p>Validates:
     * <ul>
     *   <li>NUMBER → numeric conversion in RECORD fields</li>
     *   <li>VARCHAR2 → text conversion in RECORD fields</li>
     *   <li>INTEGER type in RECORD fields</li>
     *   <li>Type conversion through to_jsonb()</li>
     *   <li>All common Oracle types work correctly</li>
     * </ul>
     */
    @Test
    void recordWithVariousTypes_typeConversion_executesSuccessfully() throws SQLException {
        // Oracle function body with various data types
        String oracleFunction = """
            FUNCTION process_data(p_count NUMBER) RETURN NUMBER IS
              TYPE data_t IS RECORD (
                numeric_field NUMBER,
                text_field VARCHAR2(200),
                integer_field INTEGER,
                float_field NUMBER
              );
              v_data data_t;
            BEGIN
              v_data.numeric_field := 12345.67;
              v_data.text_field := 'Test String Value';
              v_data.integer_field := 42;
              v_data.float_field := 3.14159;

              RETURN v_data.numeric_field + v_data.integer_field + v_data.float_field;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== TEST 5: RECORD with Various Types ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Verify function executes and calculates correctly
        // Expected: 12345.67 + 42 + 3.14159 = 12390.81159
        List<Map<String, Object>> rows = executeQuery("SELECT hr.process_data(0) AS result");
        assertEquals(1, rows.size(), "Should return exactly one row");

        double resultValue = ((Number) rows.get(0).get("result")).doubleValue();
        assertEquals(12390.81159, resultValue, 0.001, "Sum should be 12390.81159");
    }

    /**
     * Test 6: Deep nested RECORD (3 levels).
     *
     * <p>Validates:
     * <ul>
     *   <li>Three-level nested RECORD types</li>
     *   <li>Complex path arrays: {department,manager,name}</li>
     *   <li>Deep jsonb_set operations with create_if_missing=true</li>
     *   <li>Complex nested structure handling</li>
     * </ul>
     */
    @Test
    void deepNestedRecord_threeLevels_executesSuccessfully() throws SQLException {
        // Oracle function body with 3-level nested RECORD
        String oracleFunction = """
            FUNCTION setup_company RETURN NUMBER IS
              TYPE person_t IS RECORD (
                name VARCHAR2(100),
                title VARCHAR2(50)
              );
              TYPE department_t IS RECORD (
                dept_name VARCHAR2(100),
                manager person_t,
                budget NUMBER
              );
              TYPE company_t IS RECORD (
                company_name VARCHAR2(200),
                department department_t
              );
              v_company company_t;
            BEGIN
              v_company.company_name := 'Acme Corp';
              v_company.department.dept_name := 'Engineering';
              v_company.department.manager.name := 'Jane Doe';
              v_company.department.manager.title := 'VP Engineering';
              v_company.department.budget := 1000000;

              RETURN 1;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== TEST 6: Deep Nested RECORD (3 Levels) ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("=============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Verify function executes successfully
        List<Map<String, Object>> rows = executeQuery("SELECT hr.setup_company() AS result");
        assertEquals(1, rows.size(), "Should return exactly one row");

        int resultValue = ((Number) rows.get(0).get("result")).intValue();
        assertEquals(1, resultValue, "Function should return 1 (success)");
    }

    /**
     * Test 7: Real-world scenario - Complete salary calculation with RECORD types.
     *
     * <p>Validates a real-world scenario combining multiple Phase 1B features:
     * <ul>
     *   <li>Multiple RECORD types in realistic business logic</li>
     *   <li>Nested RECORDs for complex data structures</li>
     *   <li>Control flow with RECORD variables</li>
     *   <li>Arithmetic calculations using RECORD field assignments</li>
     *   <li>Complete end-to-end transformation and execution</li>
     * </ul>
     */
    @Test
    void realWorldScenario_salaryCalculation_executesSuccessfully() throws SQLException {
        // Oracle function body with realistic salary calculation
        String oracleFunction = """
            FUNCTION calculate_total_compensation(
              p_base_salary NUMBER,
              p_bonus_pct NUMBER,
              p_years_service NUMBER
            ) RETURN NUMBER IS
              TYPE salary_details_t IS RECORD (
                base NUMBER,
                bonus NUMBER,
                longevity NUMBER,
                total NUMBER
              );
              TYPE deductions_t IS RECORD (
                tax_rate NUMBER,
                tax_amount NUMBER
              );
              v_salary salary_details_t;
              v_deductions deductions_t;
              v_net_compensation NUMBER;
            BEGIN
              -- Calculate salary components
              v_salary.base := p_base_salary;
              v_salary.bonus := p_base_salary * (p_bonus_pct / 100);

              -- Longevity bonus: 2% per year of service
              IF p_years_service > 0 THEN
                v_salary.longevity := p_base_salary * (p_years_service * 0.02);
              ELSE
                v_salary.longevity := 0;
              END IF;

              v_salary.total := v_salary.base + v_salary.bonus + v_salary.longevity;

              -- Calculate tax (simplified)
              v_deductions.tax_rate := 0.25;
              v_deductions.tax_amount := v_salary.total * v_deductions.tax_rate;

              -- Calculate net compensation
              v_net_compensation := v_salary.total - v_deductions.tax_amount;

              RETURN v_net_compensation;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== TEST 7: Real-World Salary Calculation ===");
        System.out.println("Transformed Function:");
        System.out.println(result.getPostgresSql());
        System.out.println("=============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test realistic scenarios

        // Scenario 1: Base 100k, 10% bonus, 5 years service
        // Base: 100000, Bonus: 10000, Longevity: 10000, Total: 120000, Tax: 30000, Net: 90000
        List<Map<String, Object>> rows1 = executeQuery(
            "SELECT hr.calculate_total_compensation(100000, 10, 5) AS net_comp");
        double netComp1 = ((Number) rows1.get(0).get("net_comp")).doubleValue();
        assertEquals(90000.0, netComp1, 0.01, "Net compensation should be 90000.0");

        // Scenario 2: Base 50k, 5% bonus, 0 years service (new employee)
        // Base: 50000, Bonus: 2500, Longevity: 0, Total: 52500, Tax: 13125, Net: 39375
        List<Map<String, Object>> rows2 = executeQuery(
            "SELECT hr.calculate_total_compensation(50000, 5, 0) AS net_comp");
        double netComp2 = ((Number) rows2.get(0).get("net_comp")).doubleValue();
        assertEquals(39375.0, netComp2, 0.01, "Net compensation should be 39375.0");

        // Scenario 3: Base 150k, 15% bonus, 10 years service
        // Base: 150000, Bonus: 22500, Longevity: 30000, Total: 202500, Tax: 50625, Net: 151875
        List<Map<String, Object>> rows3 = executeQuery(
            "SELECT hr.calculate_total_compensation(150000, 15, 10) AS net_comp");
        double netComp3 = ((Number) rows3.get(0).get("net_comp")).doubleValue();
        assertEquals(151875.0, netComp3, 0.01, "Net compensation should be 151875.0");
    }
}
