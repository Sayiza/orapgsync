package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle cursor attributes (%FOUND, %NOTFOUND, %ROWCOUNT, %ISOPEN) transformation.
 *
 * <p>Cursor attributes are transformed to tracking variables with state updates:
 * <ul>
 *   <li>%FOUND → cursor__found (BOOLEAN)</li>
 *   <li>%NOTFOUND → NOT cursor__found</li>
 *   <li>%ROWCOUNT → cursor__rowcount (INTEGER)</li>
 *   <li>%ISOPEN → cursor__isopen (BOOLEAN)</li>
 * </ul>
 *
 * <p>State updates are injected automatically:
 * <ul>
 *   <li>OPEN: Sets cursor__isopen := TRUE</li>
 *   <li>FETCH: Sets cursor__found := FOUND; increments cursor__rowcount if FOUND</li>
 *   <li>CLOSE: Sets cursor__isopen := FALSE</li>
 * </ul>
 */
public class PostgresPlSqlCursorAttributesValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema and sample data
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("""
            CREATE TABLE hr.emp (
                empno INT PRIMARY KEY,
                ename TEXT,
                salary NUMERIC,
                dept_id INT
            )
            """);
        executeUpdate("INSERT INTO hr.emp VALUES (1, 'Alice', 50000, 10)");
        executeUpdate("INSERT INTO hr.emp VALUES (2, 'Bob', 60000, 10)");
        executeUpdate("INSERT INTO hr.emp VALUES (3, 'Charlie', 55000, 20)");
    }

    /**
     * Test 1: Basic %FOUND attribute in IF condition
     * Tests: cursor%FOUND → cursor__found
     */
    @Test
    void testCursorFoundAttribute() throws SQLException {
        String oracle = """
            FUNCTION test_found RETURN NUMBER IS
              CURSOR c IS SELECT empno FROM emp;
              v_empno NUMBER;
            BEGIN
              OPEN c;
              FETCH c INTO v_empno;
              IF c%FOUND THEN
                CLOSE c;
                RETURN 1;
              END IF;
              CLOSE c;
              RETURN 0;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed %FOUND Attribute ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================\n");

        // Verify transformation success
        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should generate tracking variables
        assertAll(
                () -> assertTrue(transformed.contains("c__found BOOLEAN;"), "Should declare c__found"),
                () -> assertTrue(transformed.contains("c__rowcount INTEGER := 0;"), "Should declare c__rowcount"),
                () -> assertTrue(transformed.contains("c__isopen BOOLEAN := FALSE;"), "Should declare c__isopen")
        );

        // OPEN should set isopen
        assertAll(
                () -> assertTrue(transformed.contains("OPEN c;"), "Should have OPEN statement"),
                () -> assertTrue(transformed.contains("c__isopen := TRUE;"), "Should set isopen to TRUE")
        );

        // FETCH should update found and rowcount
        assertAll(
                () -> assertTrue(transformed.contains("FETCH c INTO v_empno;"), "Should have FETCH statement"),
                () -> assertTrue(transformed.contains("c__found := FOUND;"), "Should update found from FOUND"),
                () -> assertTrue(transformed.contains("c__rowcount := c__rowcount + 1;"), "Should increment rowcount")
        );

        // %FOUND should be transformed
        assertTrue(transformed.contains("IF c__found THEN"), "%FOUND should be transformed to c__found");

        // CLOSE should set isopen to FALSE
        assertTrue(transformed.contains("c__isopen := FALSE;"), "CLOSE should set isopen to FALSE");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.test_found() as result");
        assertEquals(1, resultSet.size());
        assertEquals(1, ((Number) resultSet.get(0).get("result")).intValue(), "Should return 1 (found)");
    }

    /**
     * Test 2: %NOTFOUND attribute in EXIT WHEN
     * Tests: cursor%NOTFOUND → NOT cursor__found
     */
    @Test
    void testCursorNotFoundAttribute() throws SQLException {
        String oracle = """
            FUNCTION process_employees RETURN NUMBER IS
              CURSOR emp_cur IS SELECT empno FROM emp;
              v_empno NUMBER;
              v_count NUMBER := 0;
            BEGIN
              OPEN emp_cur;
              LOOP
                FETCH emp_cur INTO v_empno;
                EXIT WHEN emp_cur%NOTFOUND;
                v_count := v_count + 1;
              END LOOP;
              CLOSE emp_cur;
              RETURN v_count;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed %NOTFOUND Attribute ===");
        System.out.println(result.getPostgresSql());
        System.out.println("========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // %NOTFOUND should be transformed to NOT found
        assertTrue(transformed.contains("EXIT WHEN NOT emp_cur__found;"),
            "%NOTFOUND should be transformed to NOT emp_cur__found");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.process_employees() as result");
        assertEquals(1, resultSet.size());
        assertEquals(3, ((Number) resultSet.get(0).get("result")).intValue(), "Should count all 3 employees");
    }

    /**
     * Test 3: %ROWCOUNT attribute in expression
     * Tests: cursor%ROWCOUNT → cursor__rowcount
     */
    @Test
    void testCursorRowCountAttribute() throws SQLException {
        String oracle = """
            FUNCTION count_processed RETURN NUMBER IS
              CURSOR c IS SELECT empno FROM emp;
              v_empno NUMBER;
            BEGIN
              OPEN c;
              LOOP
                FETCH c INTO v_empno;
                EXIT WHEN c%NOTFOUND;
                IF c%ROWCOUNT > 2 THEN
                  EXIT;
                END IF;
              END LOOP;
              CLOSE c;
              RETURN c%ROWCOUNT;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed %ROWCOUNT Attribute ===");
        System.out.println(result.getPostgresSql());
        System.out.println("========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // %ROWCOUNT should be transformed
        assertAll(
                () -> assertTrue(transformed.contains("IF c__rowcount > 2 THEN"), "Should transform in condition"),
                () -> assertTrue(transformed.contains("RETURN c__rowcount;"), "Should transform in RETURN")
        );

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.count_processed() as result");
        assertEquals(1, resultSet.size());
        assertEquals(2, ((Number) resultSet.get(0).get("result")).intValue(), "Should stop at 2 rows");
    }

    /**
     * Test 4: %ISOPEN attribute in IF condition
     * Tests: cursor%ISOPEN → cursor__isopen
     */
    @Test
    void testCursorIsOpenAttribute() throws SQLException {
        String oracle = """
            FUNCTION safe_close RETURN NUMBER IS
              CURSOR c IS SELECT empno FROM emp;
              v_empno NUMBER;
            BEGIN
              OPEN c;
              FETCH c INTO v_empno;
              IF c%ISOPEN THEN
                CLOSE c;
                RETURN 1;
              END IF;
              RETURN 0;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed %ISOPEN Attribute ===");
        System.out.println(result.getPostgresSql());
        System.out.println("======================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // %ISOPEN should be transformed
        assertTrue(transformed.contains("IF c__isopen THEN"), "%ISOPEN should be transformed to c__isopen");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.safe_close() as result");
        assertEquals(1, resultSet.size());
        assertEquals(1, ((Number) resultSet.get(0).get("result")).intValue(), "Should return 1 (was open)");
    }

    /**
     * Test 5: Complete cursor lifecycle with all attributes
     */
    @Test
    void testCompleteCursorLifecycle() throws SQLException {
        String oracle = """
            FUNCTION process_data RETURN NUMBER IS
              CURSOR data_cur IS SELECT empno FROM emp WHERE dept_id = 10;
              v_id NUMBER;
              v_count NUMBER := 0;
            BEGIN
              IF NOT data_cur%ISOPEN THEN
                OPEN data_cur;
              END IF;

              LOOP
                FETCH data_cur INTO v_id;
                EXIT WHEN data_cur%NOTFOUND;

                v_count := data_cur%ROWCOUNT;

                IF data_cur%FOUND THEN
                  NULL;
                END IF;
              END LOOP;

              IF data_cur%ISOPEN THEN
                CLOSE data_cur;
              END IF;

              RETURN v_count;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed Complete Cursor Lifecycle ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==============================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // All attributes should be transformed
        assertAll(
                () -> assertTrue(transformed.contains("NOT data_cur__isopen"), "Should transform NOT %ISOPEN"),
                () -> assertTrue(transformed.contains("data_cur__isopen"), "Should transform %ISOPEN"),
                () -> assertTrue(transformed.contains("data_cur__found"), "Should transform %FOUND"),
                () -> assertTrue(transformed.contains("NOT data_cur__found"), "Should transform %NOTFOUND"),
                () -> assertTrue(transformed.contains("data_cur__rowcount"), "Should transform %ROWCOUNT")
        );

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.process_data() as result");
        assertEquals(1, resultSet.size());
        assertEquals(2, ((Number) resultSet.get(0).get("result")).intValue(), "Should process 2 rows from dept 10");
    }

    /**
     * Test 6: Multiple cursors with independent tracking
     */
    @Test
    void testMultipleCursorsIndependentTracking() throws SQLException {
        String oracle = """
            FUNCTION process_two_cursors RETURN NUMBER IS
              CURSOR c1 IS SELECT empno FROM emp WHERE dept_id = 10;
              CURSOR c2 IS SELECT empno FROM emp WHERE dept_id = 20;
              v_empno NUMBER;
              v_total NUMBER := 0;
            BEGIN
              OPEN c1;
              FETCH c1 INTO v_empno;
              IF c1%FOUND THEN
                v_total := v_total + c1%ROWCOUNT;
              END IF;
              CLOSE c1;

              OPEN c2;
              FETCH c2 INTO v_empno;
              IF c2%FOUND THEN
                v_total := v_total + c2%ROWCOUNT;
              END IF;
              CLOSE c2;

              RETURN v_total;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed Multiple Cursors ===");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should generate tracking variables for both cursors
        assertAll(
                () -> assertTrue(transformed.contains("c1__found BOOLEAN;"), "Should declare c1__found"),
                () -> assertTrue(transformed.contains("c1__rowcount INTEGER := 0;"), "Should declare c1__rowcount"),
                () -> assertTrue(transformed.contains("c2__found BOOLEAN;"), "Should declare c2__found"),
                () -> assertTrue(transformed.contains("c2__rowcount INTEGER := 0;"), "Should declare c2__rowcount")
        );

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.process_two_cursors() as result");
        assertEquals(1, resultSet.size());
        assertEquals(2, ((Number) resultSet.get(0).get("result")).intValue(), "Should sum both rowcounts (1+1)");
    }

    /**
     * Test 7: Cursor without attributes (no tracking variables generated)
     */
    @Test
    void testCursorWithoutAttributes() throws SQLException {
        String oracle = """
            FUNCTION simple_cursor RETURN NUMBER IS
              CURSOR c IS SELECT empno FROM emp WHERE dept_id = 10;
              v_empno NUMBER;
            BEGIN
              OPEN c;
              FETCH c INTO v_empno;
              CLOSE c;
              RETURN v_empno;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed Cursor Without Attributes ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==============================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should NOT generate tracking variables (cursor doesn't use attributes)
        assertAll(
                () -> assertFalse(transformed.contains("c__found"), "Should NOT declare c__found"),
                () -> assertFalse(transformed.contains("c__rowcount"), "Should NOT declare c__rowcount"),
                () -> assertFalse(transformed.contains("c__isopen"), "Should NOT declare c__isopen")
        );

        // Basic cursor operations should still work
        assertAll(
                () -> assertTrue(transformed.contains("OPEN c;"), "Should have OPEN"),
                () -> assertTrue(transformed.contains("FETCH c INTO v_empno;"), "Should have FETCH"),
                () -> assertTrue(transformed.contains("CLOSE c;"), "Should have CLOSE")
        );

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.simple_cursor() as result");
        assertEquals(1, resultSet.size());
        assertEquals(1, ((Number) resultSet.get(0).get("result")).intValue(), "Should return first empno");
    }
}
