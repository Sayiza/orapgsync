package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for Oracle collection method transformations (Phase 1E).
 *
 * <p>Tests the COMPLETE pipeline:
 * <ol>
 *   <li>Transform Oracle PL/SQL with collection methods → PostgreSQL PL/pgSQL</li>
 *   <li>Execute transformed functions in PostgreSQL (Testcontainers)</li>
 *   <li>Verify results match Oracle semantics</li>
 * </ol>
 *
 * <p><strong>Collection Methods Tested:</strong>
 * <ul>
 *   <li>COUNT → jsonb_array_length(collection)</li>
 *   <li>EXISTS(i) → jsonb_typeof(collection->(i-1)) IS NOT NULL</li>
 *   <li>FIRST → 1 (constant)</li>
 *   <li>LAST → jsonb_array_length(collection)</li>
 *   <li>DELETE(i) → collection - (i-1)</li>
 * </ul>
 *
 * <p>This validates that the transformed functions actually execute correctly in PostgreSQL
 * and produce the same results as Oracle.
 */
public class PostgresInlineTypeCollectionMethodsIntegrationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: COUNT method with simple TABLE OF collection.
     *
     * <p>Validates:
     * <ul>
     *   <li>TABLE OF type declaration transforms to jsonb</li>
     *   <li>Collection initialization with constructor syntax</li>
     *   <li>COUNT method → jsonb_array_length()</li>
     *   <li>Function executes in PostgreSQL and returns correct count</li>
     * </ul>
     */
    @Test
    void countMethod_simpleTableOf_executesCorrectly() throws SQLException {
        // Oracle function with COUNT method
        String oracleFunctionBody = """
            FUNCTION get_count RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30, 40, 50);
            BEGIN
              RETURN v_nums.COUNT;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody,
            "hr",
            indices,
            new HashMap<>(),
            "get_count",
            null
        );

        assertTrue(result.isSuccess(),
            "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================");

        // Verify transformation includes jsonb_array_length
        assertTrue(result.getPostgresSql().contains("jsonb_array_length"),
            "Should use jsonb_array_length for COUNT");

        // Execute in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Verify it returns correct count
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.get_count() AS count_value"
        );

        assertEquals(5, ((Number) rows.get(0).get("count_value")).intValue(),
            "COUNT should return 5 for array with 5 elements");

        System.out.println("✓ COUNT returned: 5");
        System.out.println("\n✅ COUNT METHOD TEST PASSED!");
    }

    /**
     * Test 2: EXISTS method with index checking.
     *
     * <p>Validates:
     * <ul>
     *   <li>EXISTS method with literal index → jsonb_typeof check</li>
     *   <li>1-based to 0-based index conversion (Oracle index 1 → PostgreSQL index 0)</li>
     *   <li>EXISTS returns TRUE for valid index</li>
     *   <li>EXISTS returns FALSE for invalid index</li>
     * </ul>
     */
    @Test
    void existsMethod_indexChecking_executesCorrectly() throws SQLException {
        // Oracle function with EXISTS method
        String oracleFunctionBody = """
            FUNCTION check_exists(p_index NUMBER) RETURN VARCHAR2 IS
              TYPE str_list_t IS TABLE OF VARCHAR2(50);
              v_items str_list_t := str_list_t('Apple', 'Banana', 'Cherry');
            BEGIN
              IF v_items.EXISTS(p_index) THEN
                RETURN 'EXISTS';
              ELSE
                RETURN 'NOT_EXISTS';
              END IF;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody,
            "hr",
            indices,
            new HashMap<>(),
            "check_exists",
            null
        );

        assertTrue(result.isSuccess(),
            "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================");

        // Verify transformation includes jsonb_typeof check
        assertTrue(result.getPostgresSql().contains("jsonb_typeof"),
            "Should use jsonb_typeof for EXISTS");

        // Execute in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Test EXISTS with valid index (1-based: Oracle index 1 exists)
        List<Map<String, Object>> existsResult = executeQuery(
            "SELECT hr.check_exists(1) AS result"
        );
        assertEquals("EXISTS", existsResult.get(0).get("result"),
            "EXISTS(1) should return TRUE for first element");

        // Test EXISTS with valid index (Oracle index 3 exists)
        List<Map<String, Object>> exists3Result = executeQuery(
            "SELECT hr.check_exists(3) AS result"
        );
        assertEquals("EXISTS", exists3Result.get(0).get("result"),
            "EXISTS(3) should return TRUE for third element");

        // Test EXISTS with invalid index (Oracle index 5 does not exist)
        List<Map<String, Object>> notExistsResult = executeQuery(
            "SELECT hr.check_exists(5) AS result"
        );
        assertEquals("NOT_EXISTS", notExistsResult.get(0).get("result"),
            "EXISTS(5) should return FALSE for non-existent index");

        System.out.println("✓ EXISTS(1): EXISTS");
        System.out.println("✓ EXISTS(3): EXISTS");
        System.out.println("✓ EXISTS(5): NOT_EXISTS");
        System.out.println("\n✅ EXISTS METHOD TEST PASSED!");
    }

    /**
     * Test 3: FIRST and LAST methods.
     *
     * <p>Validates:
     * <ul>
     *   <li>FIRST method → constant 1 (Oracle arrays always start at 1)</li>
     *   <li>LAST method → jsonb_array_length(collection)</li>
     *   <li>FOR loop with range 1..collection.LAST works correctly</li>
     * </ul>
     */
    @Test
    void firstLastMethods_forLoopIteration_executesCorrectly() throws SQLException {
        // Oracle function with FIRST and LAST methods in FOR loop
        String oracleFunctionBody = """
            FUNCTION sum_array RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30, 40);
              v_sum NUMBER := 0;
              v_first NUMBER;
              v_last NUMBER;
            BEGIN
              v_first := v_nums.FIRST;
              v_last := v_nums.LAST;

              FOR i IN v_first..v_last LOOP
                v_sum := v_sum + v_nums(i);
              END LOOP;

              RETURN v_sum;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody,
            "hr",
            indices,
            new HashMap<>(),
            "sum_array",
            null
        );

        assertTrue(result.isSuccess(),
            "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================");

        // Verify FIRST → 1
        assertTrue(result.getPostgresSql().contains("v_first := 1"),
            "FIRST should transform to constant 1");

        // Verify LAST → jsonb_array_length
        assertTrue(result.getPostgresSql().contains("v_last := jsonb_array_length"),
            "LAST should transform to jsonb_array_length");

        // Execute in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Verify sum is correct: 10 + 20 + 30 + 40 = 100
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.sum_array() AS sum_value"
        );

        assertEquals(100, ((Number) rows.get(0).get("sum_value")).intValue(),
            "Sum should be 100 (10+20+30+40)");

        System.out.println("✓ Sum: 100");
        System.out.println("\n✅ FIRST/LAST METHODS TEST PASSED!");
    }

    /**
     * Test 4: DELETE method with index.
     *
     * <p>Validates:
     * <ul>
     *   <li>DELETE method → jsonb array element removal (collection - index)</li>
     *   <li>1-based to 0-based index conversion for DELETE</li>
     *   <li>COUNT reflects new size after deletion</li>
     *   <li>Deletion works correctly in PostgreSQL</li>
     * </ul>
     */
    @Test
    void deleteMethod_removeElement_executesCorrectly() throws SQLException {
        // Oracle function with DELETE method
        String oracleFunctionBody = """
            FUNCTION delete_and_count RETURN NUMBER IS
              TYPE str_list_t IS TABLE OF VARCHAR2(10);
              v_items str_list_t := str_list_t('A', 'B', 'C', 'D', 'E');
            BEGIN
              v_items := v_items.DELETE(2);
              RETURN v_items.COUNT;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody,
            "hr",
            indices,
            new HashMap<>(),
            "delete_and_count",
            null
        );

        assertTrue(result.isSuccess(),
            "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================");

        // Verify DELETE transformation
        // For literal index: DELETE(2) → v_items - 1 (literal 2-1=1)
        // For variable index: DELETE(v_idx) → v_items - (v_idx - 1)::int
        assertTrue(result.getPostgresSql().contains("v_items := v_items - 1"),
            "DELETE(2) should transform to v_items - 1 (0-based index). Got: " + result.getPostgresSql());

        // Execute in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Verify count after deletion (5 - 1 = 4)
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.delete_and_count() AS count_value"
        );

        assertEquals(4, ((Number) rows.get(0).get("count_value")).intValue(),
            "COUNT should be 4 after deleting one element from 5-element array");

        System.out.println("✓ Count after DELETE: 4");
        System.out.println("\n✅ DELETE METHOD TEST PASSED!");
    }

    /**
     * Test 5: Complex scenario with multiple collection methods.
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple collection methods used together in one function</li>
     *   <li>COUNT, EXISTS, FIRST, LAST all work together</li>
     *   <li>Complex business logic executes correctly</li>
     * </ul>
     */
    @Test
    void multipleMethods_complexScenario_executesCorrectly() throws SQLException {
        // Oracle function using multiple collection methods
        String oracleFunctionBody = """
            FUNCTION process_collection RETURN VARCHAR2 IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(100, 200, 300, 400, 500);
              v_result VARCHAR2(200);
              v_count NUMBER;
              v_sum NUMBER := 0;
            BEGIN
              v_count := v_nums.COUNT;
              v_result := 'Count: ' || v_count;

              IF v_nums.EXISTS(3) THEN
                v_result := v_result || ', Third element exists';
              END IF;

              FOR i IN v_nums.FIRST..v_nums.LAST LOOP
                v_sum := v_sum + v_nums(i);
              END LOOP;

              v_result := v_result || ', Sum: ' || v_sum;
              RETURN v_result;
            END;
            """;

        // Transform to PostgreSQL
        TransformationResult result = transformationService.transformFunction(
            oracleFunctionBody,
            "hr",
            indices,
            new HashMap<>(),
            "process_collection",
            null
        );

        assertTrue(result.isSuccess(),
            "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed Function ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================");

        // Execute in PostgreSQL
        executeUpdate(result.getPostgresSql());

        // Verify complex result
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.process_collection() AS result"
        );

        String resultStr = (String) rows.get(0).get("result");

        assertTrue(resultStr.contains("Count: 5"),
            "Result should include count of 5");
        assertTrue(resultStr.contains("Third element exists"),
            "Result should confirm third element exists");
        assertTrue(resultStr.contains("Sum: 1500"),
            "Result should include sum of 1500 (100+200+300+400+500)");

        System.out.println("✓ Result: " + resultStr);
        System.out.println("\n✅ COMPLEX SCENARIO TEST PASSED!");
    }
}
