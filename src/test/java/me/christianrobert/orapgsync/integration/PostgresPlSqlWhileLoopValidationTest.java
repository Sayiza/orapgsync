package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL validation tests for PL/SQL WHILE loops.
 *
 * <p>Tests the transformation of Oracle WHILE loops to PostgreSQL equivalents.
 * Syntax is identical in both Oracle and PostgreSQL.
 *
 * <p>Test Strategy:
 * <ul>
 *   <li>Transform complete Oracle functions with WHILE loops</li>
 *   <li>Execute transformed PL/pgSQL in PostgreSQL</li>
 *   <li>Call functions with various inputs to test all scenarios</li>
 *   <li>Validates: syntax correctness, loop control flow, result correctness</li>
 * </ul>
 *
 * <p>Scope:
 * <ul>
 *   <li>✅ WHILE condition LOOP - Basic conditional loop</li>
 *   <li>✅ WHILE with simple conditions - Counter-based loops</li>
 *   <li>✅ WHILE with complex conditions - Multiple predicates</li>
 *   <li>✅ WHILE with EXIT - Early termination</li>
 *   <li>✅ WHILE with CONTINUE - Skip iterations</li>
 *   <li>✅ Nested WHILE loops</li>
 *   <li>✅ Labeled WHILE loops</li>
 * </ul>
 *
 * <h3>Oracle vs PostgreSQL Syntax:</h3>
 * <pre>
 * Oracle:                          PostgreSQL:
 * WHILE condition LOOP             WHILE condition LOOP        (identical)
 *   statements                       statements
 * END LOOP;                        END LOOP;
 * </pre>
 */
public class PostgresPlSqlWhileLoopValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Simple WHILE loop with counter.
     *
     * <p>Validates:
     * <ul>
     *   <li>Basic WHILE condition LOOP structure</li>
     *   <li>Counter increment in loop</li>
     *   <li>Loop termination when condition becomes false</li>
     *   <li>Accumulator pattern</li>
     * </ul>
     */
    @Test
    void whileLoop_simpleCounter() throws SQLException {
        // Oracle function with simple WHILE loop
        String oracleFunction = """
            FUNCTION sum_while_loop(p_limit NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 1;
              v_total NUMBER := 0;
            BEGIN
              WHILE v_counter <= p_limit LOOP
                v_total := v_total + v_counter;
                v_counter := v_counter + 1;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Simple WHILE Loop ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        // Verify transformation succeeded
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // Execute transformed function in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Sum of 1 to 10: 1+2+3+4+5+6+7+8+9+10 = 55
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_while_loop(10) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(55.0, total, 0.01, "Should sum 1 to 10 = 55");

        // Sum of 1 to 5: 1+2+3+4+5 = 15
        rows = executeQuery("SELECT hr.sum_while_loop(5) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(15.0, total, 0.01, "Should sum 1 to 5 = 15");
    }

    /**
     * Test 2: WHILE loop with complex condition.
     *
     * <p>Validates:
     * <ul>
     *   <li>WHILE with multiple predicates (AND condition)</li>
     *   <li>Loop termination when any predicate fails</li>
     *   <li>Boundary condition handling</li>
     * </ul>
     */
    @Test
    void whileLoop_complexCondition() throws SQLException {
        // Oracle function with complex WHILE condition
        String oracleFunction = """
            FUNCTION sum_until_both_limits(p_count_limit NUMBER, p_sum_limit NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 1;
              v_total NUMBER := 0;
            BEGIN
              WHILE v_counter <= p_count_limit AND v_total < p_sum_limit LOOP
                v_total := v_total + v_counter;
                v_counter := v_counter + 1;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed WHILE with Complex Condition ===");
        System.out.println(result.getPostgresSql());
        System.out.println("================================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Count limit: 10, Sum limit: 20 → stops when sum reaches 21 (1+2+3+4+5+6=21, counter=7)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_until_both_limits(10, 20) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(21.0, total, 0.01, "Should sum until reaching 21");

        // Count limit: 3, Sum limit: 100 → stops when counter > 3 (1+2+3=6)
        rows = executeQuery("SELECT hr.sum_until_both_limits(3, 100) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(6.0, total, 0.01, "Should sum 1+2+3 = 6");
    }

    /**
     * Test 3: WHILE loop with EXIT - early termination.
     *
     * <p>Validates:
     * <ul>
     *   <li>Combining WHILE with EXIT statement</li>
     *   <li>Early termination before WHILE condition fails</li>
     *   <li>Multiple exit conditions</li>
     * </ul>
     */
    @Test
    void whileLoop_withExit() throws SQLException {
        // Oracle function with WHILE and EXIT
        String oracleFunction = """
            FUNCTION find_threshold(p_max_count NUMBER, p_threshold NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 1;
              v_total NUMBER := 0;
            BEGIN
              WHILE v_counter <= p_max_count LOOP
                v_total := v_total + v_counter;
                EXIT WHEN v_total >= p_threshold;
                v_counter := v_counter + 1;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed WHILE with EXIT ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Max count 100, threshold 15 → exits when sum reaches 15 (1+2+3+4+5=15)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.find_threshold(100, 15) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(15.0, total, 0.01, "Should exit when reaching 15");

        // Max count 3, threshold 100 → WHILE condition stops at 3 (1+2+3=6)
        rows = executeQuery("SELECT hr.find_threshold(3, 100) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(6.0, total, 0.01, "Should stop at counter 3 = 6");
    }

    /**
     * Test 4: WHILE loop with CONTINUE - skip iterations.
     *
     * <p>Validates:
     * <ul>
     *   <li>Combining WHILE with CONTINUE statement</li>
     *   <li>Selective processing of iterations</li>
     *   <li>Proper loop continuation after CONTINUE</li>
     * </ul>
     */
    @Test
    void whileLoop_withContinue() throws SQLException {
        // Oracle function with WHILE and CONTINUE
        String oracleFunction = """
            FUNCTION sum_selective(p_max NUMBER, p_skip_threshold NUMBER) RETURN NUMBER IS
              v_counter NUMBER := 0;
              v_total NUMBER := 0;
            BEGIN
              WHILE v_counter < p_max LOOP
                v_counter := v_counter + 1;
                CONTINUE WHEN v_counter <= p_skip_threshold;
                v_total := v_total + v_counter;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed WHILE with CONTINUE ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Skip first 5, sum 6-10: 6+7+8+9+10 = 40
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_selective(10, 5) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(40.0, total, 0.01, "Should sum 6-10 = 40");

        // Skip first 2, sum 3-6: 3+4+5+6 = 18
        rows = executeQuery("SELECT hr.sum_selective(6, 2) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(18.0, total, 0.01, "Should sum 3-6 = 18");
    }

    /**
     * Test 5: Nested WHILE loops.
     *
     * <p>Validates:
     * <ul>
     *   <li>Nested WHILE loop structures</li>
     *   <li>Independent loop variables</li>
     *   <li>Proper scoping of conditions</li>
     *   <li>Multiplication table pattern</li>
     * </ul>
     */
    @Test
    void whileLoop_nested() throws SQLException {
        // Oracle function with nested WHILE loops
        String oracleFunction = """
            FUNCTION count_combinations(p_outer_max NUMBER, p_inner_max NUMBER) RETURN NUMBER IS
              v_outer NUMBER := 0;
              v_inner NUMBER;
              v_count NUMBER := 0;
            BEGIN
              WHILE v_outer < p_outer_max LOOP
                v_outer := v_outer + 1;
                v_inner := 0;

                WHILE v_inner < p_inner_max LOOP
                  v_inner := v_inner + 1;
                  v_count := v_count + 1;
                END LOOP;
              END LOOP;
              RETURN v_count;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Nested WHILE Loops ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // 3 outer * 4 inner = 12 combinations
        List<Map<String, Object>> rows = executeQuery("SELECT hr.count_combinations(3, 4) AS count");
        int count = ((Number) rows.get(0).get("count")).intValue();
        assertEquals(12, count, "Should count 3*4 = 12 combinations");

        // 5 outer * 5 inner = 25 combinations
        rows = executeQuery("SELECT hr.count_combinations(5, 5) AS count");
        count = ((Number) rows.get(0).get("count")).intValue();
        assertEquals(25, count, "Should count 5*5 = 25 combinations");
    }

    /**
     * Test 6: Labeled WHILE loop with EXIT label.
     *
     * <p>Validates:
     * <ul>
     *   <li>&lt;&lt;label_name&gt;&gt; WHILE syntax</li>
     *   <li>EXIT label_name to exit specific WHILE loop</li>
     *   <li>Early termination of outer loop from inner loop</li>
     *   <li>END LOOP label_name syntax</li>
     * </ul>
     */
    @Test
    void whileLoop_labeledWithExit() throws SQLException {
        // Oracle function with labeled WHILE and EXIT label
        String oracleFunction = """
            FUNCTION find_pair_sum(p_target NUMBER, p_max NUMBER) RETURN NUMBER IS
              v_outer NUMBER := 0;
              v_inner NUMBER;
              v_sum NUMBER;
            BEGIN
              <<outer_while>>
              WHILE v_outer < p_max LOOP
                v_outer := v_outer + 1;
                v_inner := 0;

                WHILE v_inner < p_max LOOP
                  v_inner := v_inner + 1;
                  v_sum := v_outer + v_inner;

                  IF v_sum = p_target THEN
                    EXIT outer_while;
                  END IF;
                END LOOP;
              END LOOP outer_while;

              RETURN v_outer;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed Labeled WHILE with EXIT ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Target 5: First match at v_outer=1, v_inner=4 (1+4=5)
        List<Map<String, Object>> rows = executeQuery("SELECT hr.find_pair_sum(5, 10) AS outer_val");
        int outerVal = ((Number) rows.get(0).get("outer_val")).intValue();
        assertEquals(1, outerVal, "Should find first match at outer=1");

        // Target 15: First match at v_outer=1, v_inner=14 (1+14=15)
        rows = executeQuery("SELECT hr.find_pair_sum(15, 20) AS outer_val");
        outerVal = ((Number) rows.get(0).get("outer_val")).intValue();
        assertEquals(1, outerVal, "Should find first match at outer=1");
    }

    /**
     * Test 7: WHILE loop with zero iterations.
     *
     * <p>Validates:
     * <ul>
     *   <li>WHILE condition false from start</li>
     *   <li>Loop body never executes</li>
     *   <li>Proper handling of edge case</li>
     * </ul>
     */
    @Test
    void whileLoop_zeroIterations() throws SQLException {
        // Oracle function with WHILE that might not execute
        String oracleFunction = """
            FUNCTION conditional_sum(p_start NUMBER, p_end NUMBER) RETURN NUMBER IS
              v_counter NUMBER := p_start;
              v_total NUMBER := 0;
            BEGIN
              WHILE v_counter <= p_end LOOP
                v_total := v_total + v_counter;
                v_counter := v_counter + 1;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed WHILE with Zero Iterations ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==============================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution
        // Normal case: sum 5 to 8: 5+6+7+8 = 26
        List<Map<String, Object>> rows = executeQuery("SELECT hr.conditional_sum(5, 8) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(26.0, total, 0.01, "Should sum 5-8 = 26");

        // Zero iterations: start > end (10 > 5)
        rows = executeQuery("SELECT hr.conditional_sum(10, 5) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(0.0, total, 0.01, "Should return 0 for zero iterations");
    }

    /**
     * Test 8: WHILE vs FOR loop equivalence.
     *
     * <p>Validates:
     * <ul>
     *   <li>WHILE loop can replicate FOR loop behavior</li>
     *   <li>Manual counter management</li>
     *   <li>Results match equivalent FOR loop</li>
     * </ul>
     */
    @Test
    void whileLoop_equivalentToFor() throws SQLException {
        // Oracle function with WHILE that replicates FOR loop
        String oracleFunction = """
            FUNCTION sum_range_while(p_start NUMBER, p_end NUMBER) RETURN NUMBER IS
              v_counter NUMBER := p_start;
              v_total NUMBER := 0;
            BEGIN
              WHILE v_counter <= p_end LOOP
                v_total := v_total + v_counter;
                v_counter := v_counter + 1;
              END LOOP;
              RETURN v_total;
            END;
            """;

        // Transform
        TransformationResult result = transformationService.transformFunction(oracleFunction, "hr", indices);

        // Debug output
        System.out.println("=== Transformed WHILE Equivalent to FOR ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===========================================");

        // Verify and execute
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        executeUpdate(result.getPostgresSql());

        // Test function execution - should match FOR loop results
        // Sum 1 to 10: 1+2+3+4+5+6+7+8+9+10 = 55
        List<Map<String, Object>> rows = executeQuery("SELECT hr.sum_range_while(1, 10) AS total");
        double total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(55.0, total, 0.01, "Should sum 1-10 = 55 (same as FOR loop)");

        // Sum 5 to 10: 5+6+7+8+9+10 = 45
        rows = executeQuery("SELECT hr.sum_range_while(5, 10) AS total");
        total = ((Number) rows.get(0).get("total")).doubleValue();
        assertEquals(45.0, total, 0.01, "Should sum 5-10 = 45");
    }
}
