package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL basic LOOP, EXIT, and CONTINUE statements.
 *
 * <p>Tests the transformation of Oracle basic LOOP statements with EXIT and CONTINUE control
 * flow to PostgreSQL equivalents. Syntax is identical in both Oracle and PostgreSQL.
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with basic LOOP statements</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all scenarios</li>
 *   <li>Validates: syntax correctness, loop control flow, result correctness</li>
 * </ul>
 *
 * <p>Scope:
 * <ul>
 *   <li>✅ LOOP...END LOOP - Basic infinite loop structure</li>
 *   <li>✅ EXIT - Unconditional loop exit</li>
 *   <li>✅ EXIT WHEN condition - Conditional loop exit</li>
 *   <li>✅ CONTINUE - Skip to next iteration</li>
 *   <li>✅ CONTINUE WHEN condition - Conditional skip</li>
 *   <li>✅ Labeled loops with EXIT label</li>
 * </ul>
 *
 * <h3>Oracle vs PostgreSQL Syntax:</h3>
 * <pre>
 * Oracle:                          PostgreSQL:
 * LOOP ... END LOOP;               LOOP ... END LOOP;          (identical)
 * EXIT;                            EXIT;                       (identical)
 * EXIT WHEN condition;             EXIT WHEN condition;        (identical)
 * CONTINUE;                        CONTINUE;                   (identical)
 * CONTINUE WHEN condition;         CONTINUE WHEN condition;    (identical)
 * &lt;&lt;label&gt;&gt; LOOP ... EXIT label;   &lt;&lt;label&gt;&gt; LOOP ... EXIT label;  (identical)
 * </pre>
 */
public class PostgresPlSqlBasicLoopValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Simple basic LOOP with EXIT - sum until threshold.
     *
     * <p>Validates:
     * <ul>
     *   <li>Basic LOOP...END LOOP structure</li>
     *   <li>Counter increment in loop</li>
     *   <li>Unconditional EXIT statement</li>
     *   <li>Loop termination after reaching threshold</li>
     * </ul>
     */
    @Test
    void basicLoop_simpleCounterWithExit() throws SQLException {
        // Oracle function with basic LOOP and unconditional EXIT
        String oracleFunction = """
            FUNCTION count_to_five RETURN NUMBER IS
              v_counter NUMBER := 0;
            BEGIN
              LOOP
                v_counter := v_counter + 1;
                IF v_counter = 5 THEN
                  EXIT;
                END IF;
              END LOOP;
              RETURN v_counter;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Basic LOOP with EXIT ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=========================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        List<Map<String, Object>> rows = executeQuery("SELECT hr.count_to_five() AS counter");
        int counter = ((Number) rows.get(0).get("counter")).intValue();
        assertEquals(5, counter, "Should count to 5");
    }

    /**
     * Test 2: Basic LOOP with EXIT WHEN - sum numbers until reaching limit.
     *
     * <p>Validates:
     * <ul>
     *   <li>EXIT WHEN condition syntax</li>
     *   <li>Conditional loop termination</li>
     *   <li>Accumulator pattern in loop</li>
     * </ul>
     */
    @Test
    void basicLoop_exitWhenCondition() throws SQLException {
        // Oracle function with EXIT WHEN
        String oracleFunction = """
            FUNCTION sum_until_limit(p_limit NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 1;
              v_total NUMBER := 0;
            BEGIN
              LOOP
                v_total := v_total + v_counter;
                v_counter := v_counter + 1;
                EXIT WHEN v_total >= p_limit;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed LOOP with EXIT WHEN ===");
        System.out.println(result.getPostgresSql());
        System.out.println("========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Sums: 1, 1+2=3, 3+3=6, 6+4=10, 10+5=15 (stops when >= 15)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_until_limit(15) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(15.0, total, 0.01, "Should sum until reaching 15");

        // Sums: 1, 1+2=3, 3+3=6 (stops when >= 5)
        rows = executeQuery("SELECT hr.sum_until_limit(5) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(6.0, total, 0.01, "Should sum until reaching/exceeding 5 = 6");
    }

    /**
     * Test 3: Basic LOOP with CONTINUE WHEN - skip certain values.
     *
     * <p>Validates:
     * <ul>
     *   <li>CONTINUE WHEN condition syntax</li>
     *   <li>Skipping iterations conditionally</li>
     *   <li>Loop continues after CONTINUE</li>
     *   <li>Filtering pattern (skip values below threshold)</li>
     * </ul>
     */
    @Test
    void basicLoop_continueWhenCondition() throws SQLException {
        // Oracle function with CONTINUE WHEN - skip small values
        String oracleFunction = """
            FUNCTION sum_large_values(p_threshold NUMBER, p_max NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 0;
              v_total NUMBER := 0;
            BEGIN
              LOOP
                v_counter := v_counter + 1;
                EXIT WHEN v_counter > p_max;
                CONTINUE WHEN v_counter <= p_threshold;
                v_total := v_total + v_counter;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed LOOP with CONTINUE WHEN ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Skip values <= 5 from 1 to 10: 6+7+8+9+10 = 40
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_large_values(5, 10) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(40.0, total, 0.01, "Should sum values > 5: 6+7+8+9+10 = 40");

        // Skip values <= 3 from 1 to 6: 4+5+6 = 15
        rows = executeQuery("SELECT hr.sum_large_values(3, 6) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(15.0, total, 0.01, "Should sum values > 3: 4+5+6 = 15");
    }

    /**
     * Test 4: Nested basic LOOPs with multiple EXIT conditions.
     *
     * <p>Validates:
     * <ul>
     *   <li>Nested LOOP structures</li>
     *   <li>Multiple EXIT statements in inner and outer loops</li>
     *   <li>Proper loop variable scoping</li>
     *   <li>EXIT only exits innermost loop (unless labeled)</li>
     * </ul>
     */
    @Test
    void nestedBasicLoops_multipleExitConditions() throws SQLException {
        // Oracle function with nested basic LOOPs
        String oracleFunction = """
            FUNCTION count_nested_iterations(p_outer_max NUMBER, p_inner_max NUMBER) RETURN NUMBER IS
              v_outer NUMBER := 0;
              v_inner NUMBER;
              v_count NUMBER := 0;
            BEGIN
              LOOP
                v_outer := v_outer + 1;
                EXIT WHEN v_outer > p_outer_max;

                v_inner := 0;
                LOOP
                  v_inner := v_inner + 1;
                  EXIT WHEN v_inner > p_inner_max;
                  v_count := v_count + 1;
                END LOOP;
              END LOOP;
              RETURN v_count;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Nested Basic LOOPs ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // 3 outer iterations * 4 inner iterations = 12 total
        List<Map<String, Object>> rows = executeQuery("SELECT hr.count_nested_iterations(3, 4) AS count");
        int count = ((Number) rows.get(0).get("count")).intValue();
        assertEquals(12, count, "Should count 3*4 = 12 iterations");

        // 2 outer iterations * 5 inner iterations = 10 total
        rows = executeQuery("SELECT hr.count_nested_iterations(2, 5) AS count");
        count = ((Number) rows.get(0).get("count")).intValue();
        assertEquals(10, count, "Should count 2*5 = 10 iterations");
    }

    /**
     * Test 5: Labeled LOOP with EXIT label - exit outer loop from inner loop.
     *
     * <p>Validates:
     * <ul>
     *   <li>&lt;&lt;label_name&gt;&gt; LOOP syntax</li>
     *   <li>EXIT label_name to exit specific loop</li>
     *   <li>Early termination of outer loop from inner loop</li>
     *   <li>END LOOP label_name syntax</li>
     * </ul>
     */
    @Test
    void labeledLoop_exitOuterFromInner() throws SQLException {
        // Oracle function with labeled LOOP and EXIT label
        String oracleFunction = """
            FUNCTION find_first_match(p_target NUMBER) RETURN NUMBER IS
              v_outer NUMBER := 0;
              v_inner NUMBER;
              v_sum NUMBER;
            BEGIN
              <<outer_loop>>
              LOOP
                v_outer := v_outer + 1;
                EXIT outer_loop WHEN v_outer > 10;

                v_inner := 0;
                LOOP
                  v_inner := v_inner + 1;
                  EXIT WHEN v_inner > 10;

                  v_sum := v_outer + v_inner;
                  IF v_sum = p_target THEN
                    EXIT outer_loop;
                  END IF;
                END LOOP;
              END LOOP outer_loop;

              RETURN v_outer;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Labeled LOOP with EXIT label ===");
        System.out.println(result.getPostgresSql());
        System.out.println("================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Target 5: First match at v_outer=1, v_inner=4 (1+4=5)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.find_first_match(5) AS outer_val");
        int outerVal = ((Number) rows.get(0).get("outer_val")).intValue();
        assertEquals(1, outerVal, "Should find first match at outer=1");

        // Target 15: First match at v_outer=5, v_inner=10 (5+10=15)
        rows = executeQuery("SELECT hr.find_first_match(15) AS outer_val");
        outerVal = ((Number) rows.get(0).get("outer_val")).intValue();
        assertEquals(5, outerVal, "Should find first match at outer=5");
    }

    /**
     * Test 6: LOOP with both EXIT WHEN and CONTINUE WHEN - complex control flow.
     *
     * <p>Validates:
     * <ul>
     *   <li>Combining EXIT WHEN and CONTINUE WHEN in same loop</li>
     *   <li>Conditional branching with multiple control statements</li>
     *   <li>Skip certain values, exit on limit</li>
     * </ul>
     */
    @Test
    void basicLoop_combinedExitAndContinue() throws SQLException {
        // Oracle function with both EXIT WHEN and CONTINUE WHEN
        String oracleFunction = """
            FUNCTION sum_filtered_range(p_skip_start NUMBER, p_skip_end NUMBER, p_max NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 0;
              v_total NUMBER := 0;
            BEGIN
              LOOP
                v_counter := v_counter + 1;
                EXIT WHEN v_counter > p_max;
                CONTINUE WHEN v_counter >= p_skip_start AND v_counter <= p_skip_end;
                v_total := v_total + v_counter;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed LOOP with EXIT and CONTINUE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Skip 2-5 from 1 to 10: 1 + 6+7+8+9+10 = 41
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_filtered_range(2, 5, 10) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(41.0, total, 0.01, "Should sum 1 + 6-10 = 41");

        // Skip 3-4 from 1 to 6: 1+2 + 5+6 = 14
        rows = executeQuery("SELECT hr.sum_filtered_range(3, 4, 6) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(14.0, total, 0.01, "Should sum 1+2+5+6 = 14");
    }

    /**
     * Test 7: Basic LOOP with unconditional CONTINUE - infinite loop protection.
     *
     * <p>Validates:
     * <ul>
     *   <li>CONTINUE without WHEN (unconditional skip)</li>
     *   <li>Used with IF condition for selective processing</li>
     *   <li>Proper placement of CONTINUE to avoid unreachable code</li>
     * </ul>
     */
    @Test
    void basicLoop_unconditionalContinue() throws SQLException {
        // Oracle function with unconditional CONTINUE in IF block
        String oracleFunction = """
            FUNCTION sum_greater_than(p_threshold NUMBER, p_max NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 0;
              v_total NUMBER := 0;
            BEGIN
              LOOP
                v_counter := v_counter + 1;
                EXIT WHEN v_counter > p_max;

                IF v_counter <= p_threshold THEN
                  CONTINUE;
                END IF;

                v_total := v_total + v_counter;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed LOOP with Unconditional CONTINUE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Sum numbers > 5 from 1 to 10: 6+7+8+9+10 = 40
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_greater_than(5, 10) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(40.0, total, 0.01, "Should sum numbers > 5: 6+7+8+9+10 = 40");

        // Sum numbers > 3 from 1 to 6: 4+5+6 = 15
        rows = executeQuery("SELECT hr.sum_greater_than(3, 6) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(15.0, total, 0.01, "Should sum numbers > 3: 4+5+6 = 15");
    }
}
