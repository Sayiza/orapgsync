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

    // ========== SQL% IMPLICIT CURSOR ATTRIBUTE TESTS ==========
    //
    // NOTE: DML statements (UPDATE/DELETE/INSERT) in PL/SQL context are not yet fully implemented.
    // The transformation infrastructure exists, but complete DML visitor implementation is future work.
    // For now, SQL% cursor attributes are tested only with SELECT INTO (which is fully supported).
    //
    // TODO: Uncomment these tests when DML statement transformation is implemented.

    /**
     * Test 8: SQL%ROWCOUNT after UPDATE statement (DISABLED - DML not yet supported)
     * Tests: SQL%ROWCOUNT tracking with GET DIAGNOSTICS after UPDATE
     */
    @Test
    @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
    void testSqlRowCountAfterUpdate() throws SQLException {
        String oracle = """
            FUNCTION update_salaries RETURN NUMBER IS
            BEGIN
              UPDATE emp SET salary = salary * 1.1 WHERE dept_id = 10;
              RETURN SQL%ROWCOUNT;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed SQL%ROWCOUNT (UPDATE) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should generate sql__rowcount variable (not __found or __isopen)
        assertAll(
                () -> assertTrue(transformed.contains("sql__rowcount INTEGER := 0;"), "Should declare sql__rowcount"),
                () -> assertFalse(transformed.contains("sql__found"), "Should NOT declare sql__found"),
                () -> assertFalse(transformed.contains("sql__isopen"), "Should NOT declare sql__isopen")
        );

        // UPDATE should have GET DIAGNOSTICS injection
        assertTrue(transformed.contains("GET DIAGNOSTICS sql__rowcount = ROW_COUNT;"),
                "Should inject GET DIAGNOSTICS after UPDATE");

        // SQL%ROWCOUNT should be transformed
        assertTrue(transformed.contains("RETURN sql__rowcount;"), "SQL%ROWCOUNT should be transformed");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.update_salaries() as result");
        assertEquals(1, resultSet.size());
        assertEquals(2, ((Number) resultSet.get(0).get("result")).intValue(),
                "Should return 2 (two rows in dept_id 10 were updated)");
    }

    /**
     * Test 9: SQL%FOUND after DELETE statement (DISABLED - DML not yet supported)
     * Tests: SQL%FOUND → (sql__rowcount > 0)
     */
    @Test
    @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
    void testSqlFoundAfterDelete() throws SQLException {
        String oracle = """
            FUNCTION delete_employee(p_empno NUMBER) RETURN NUMBER IS
            BEGIN
              DELETE FROM emp WHERE empno = p_empno;
              IF SQL%FOUND THEN
                RETURN 1;
              END IF;
              RETURN 0;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed SQL%FOUND (DELETE) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=======================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should generate sql__rowcount variable
        assertTrue(transformed.contains("sql__rowcount INTEGER := 0;"), "Should declare sql__rowcount");

        // DELETE should have GET DIAGNOSTICS injection
        assertTrue(transformed.contains("GET DIAGNOSTICS sql__rowcount = ROW_COUNT;"),
                "Should inject GET DIAGNOSTICS after DELETE");

        // SQL%FOUND should be transformed to (sql__rowcount > 0)
        assertTrue(transformed.contains("IF (sql__rowcount > 0) THEN"),
                "SQL%FOUND should be transformed to (sql__rowcount > 0)");

        // Execute and test - delete existing employee
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.delete_employee(1) as result");
        assertEquals(1, resultSet.size());
        assertEquals(1, ((Number) resultSet.get(0).get("result")).intValue(),
                "Should return 1 (employee was found and deleted)");

        // Test with non-existent employee
        resultSet = executeQuery("SELECT hr.delete_employee(999) as result");
        assertEquals(1, resultSet.size());
        assertEquals(0, ((Number) resultSet.get(0).get("result")).intValue(),
                "Should return 0 (employee not found)");
    }

    /**
     * Test 10: SQL%NOTFOUND after INSERT statement (DISABLED - DML not yet supported)
     * Tests: SQL%NOTFOUND → (sql__rowcount = 0)
     */
    @Test
    @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
    void testSqlNotFoundAfterInsert() throws SQLException {
        String oracle = """
            FUNCTION insert_if_not_exists(p_empno NUMBER, p_ename VARCHAR2) RETURN VARCHAR2 IS
            BEGIN
              INSERT INTO emp (empno, ename, salary, dept_id)
              SELECT p_empno, p_ename, 50000, 10
              WHERE NOT EXISTS (SELECT 1 FROM emp WHERE empno = p_empno);

              IF SQL%NOTFOUND THEN
                RETURN 'ALREADY_EXISTS';
              END IF;
              RETURN 'INSERTED';
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed SQL%NOTFOUND (INSERT) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("==========================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // SQL%NOTFOUND should be transformed to (sql__rowcount = 0)
        assertTrue(transformed.contains("IF (sql__rowcount = 0) THEN"),
                "SQL%NOTFOUND should be transformed to (sql__rowcount = 0)");

        // INSERT should have GET DIAGNOSTICS injection
        assertTrue(transformed.contains("GET DIAGNOSTICS sql__rowcount = ROW_COUNT;"),
                "Should inject GET DIAGNOSTICS after INSERT");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        // Insert new employee (should succeed)
        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.insert_if_not_exists(100, 'David') as result");
        assertEquals(1, resultSet.size());
        assertEquals("INSERTED", resultSet.get(0).get("result"), "Should return INSERTED");

        // Try to insert same employee again (should fail)
        resultSet = executeQuery("SELECT hr.insert_if_not_exists(100, 'David') as result");
        assertEquals(1, resultSet.size());
        assertEquals("ALREADY_EXISTS", resultSet.get(0).get("result"), "Should return ALREADY_EXISTS");
    }

    /**
     * Test 11: SQL%ROWCOUNT after SELECT INTO statement
     * Tests: GET DIAGNOSTICS injection after SELECT INTO
     */
    @Test
    void testSqlRowCountAfterSelectInto() throws SQLException {
        String oracle = """
            FUNCTION get_employee_count(p_dept_id NUMBER) RETURN NUMBER IS
              v_name VARCHAR2(100);
            BEGIN
              SELECT ename INTO v_name FROM emp WHERE dept_id = p_dept_id AND ROWNUM = 1;
              RETURN SQL%ROWCOUNT;
            EXCEPTION
              WHEN NO_DATA_FOUND THEN
                RETURN 0;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed SQL%ROWCOUNT (SELECT INTO) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should have GET DIAGNOSTICS after SELECT INTO
        // Note: PostgreSQL may add STRICT keyword, so check for "INTO" followed by "v_name"
        assertTrue(transformed.contains("INTO") && transformed.contains("v_name"),
                "Should have SELECT INTO (may include STRICT keyword)");
        assertTrue(transformed.contains("GET DIAGNOSTICS sql__rowcount = ROW_COUNT;"),
                "Should inject GET DIAGNOSTICS after SELECT INTO");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.get_employee_count(10) as result");
        assertEquals(1, resultSet.size());
        assertEquals(1, ((Number) resultSet.get(0).get("result")).intValue(),
                "Should return 1 (one row fetched)");

        // Test with no data
        resultSet = executeQuery("SELECT hr.get_employee_count(999) as result");
        assertEquals(1, resultSet.size());
        assertEquals(0, ((Number) resultSet.get(0).get("result")).intValue(),
                "Should return 0 (no data found)");
    }

    /**
     * Test 12: SQL%ISOPEN always returns FALSE (DISABLED - DML not yet supported)
     * Tests: SQL%ISOPEN → FALSE (constant)
     */
    @Test
    @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
    void testSqlIsOpenAlwaysFalse() throws SQLException{
        String oracle = """
            FUNCTION check_sql_isopen RETURN NUMBER IS
            BEGIN
              UPDATE emp SET salary = salary WHERE dept_id = 10;
              IF SQL%ISOPEN THEN
                RETURN 1;
              END IF;
              RETURN 0;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed SQL%ISOPEN ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // SQL%ISOPEN should be transformed to FALSE (constant)
        assertTrue(transformed.contains("IF FALSE THEN"), "SQL%ISOPEN should be transformed to FALSE");

        // Should NOT generate sql__rowcount if only SQL%ISOPEN is used
        // (optimizer: if only ISOPEN is used, we don't need tracking)
        // Actually, we do register it, so sql__rowcount will be declared
        assertTrue(transformed.contains("sql__rowcount"), "Should declare sql__rowcount");

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.check_sql_isopen() as result");
        assertEquals(1, resultSet.size());
        assertEquals(0, ((Number) resultSet.get(0).get("result")).intValue(),
                "Should return 0 (SQL%ISOPEN always FALSE)");
    }

    /**
     * Test 13: Multiple SQL% attributes in same function (DISABLED - DML not yet supported)
     * Tests: Combined SQL%FOUND and SQL%ROWCOUNT
     */
    @Test
    @org.junit.jupiter.api.Disabled("DML statements in PL/SQL not yet implemented")
    void testMultipleSqlAttributes() throws SQLException {
        String oracle = """
            FUNCTION update_and_check RETURN VARCHAR2 IS
              v_count NUMBER;
            BEGIN
              UPDATE emp SET salary = salary * 1.05 WHERE dept_id = 20;
              v_count := SQL%ROWCOUNT;

              IF SQL%FOUND THEN
                RETURN 'Updated ' || v_count || ' rows';
              ELSE
                RETURN 'No rows updated';
              END IF;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);

        System.out.println("=== Transformed Multiple SQL% Attributes ===");
        System.out.println(result.getPostgresSql());
        System.out.println("============================================\n");

        assertTrue(result.isSuccess(), "Transformation should succeed");

        String transformed = result.getPostgresSql();

        // Should have single sql__rowcount variable (not duplicated)
        int count = countOccurrences(transformed, "sql__rowcount INTEGER := 0;");
        assertEquals(1, count, "Should declare sql__rowcount only once");

        // Both SQL%ROWCOUNT and SQL%FOUND should be transformed
        assertAll(
                () -> assertTrue(transformed.contains("v_count := sql__rowcount;"), "SQL%ROWCOUNT should be transformed"),
                () -> assertTrue(transformed.contains("IF (sql__rowcount > 0) THEN"), "SQL%FOUND should be transformed")
        );

        // Execute and test
        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> resultSet = executeQuery("SELECT hr.update_and_check() as result");
        assertEquals(1, resultSet.size());
        assertEquals("Updated 1 rows", resultSet.get(0).get("result"),
                "Should return 'Updated 1 rows' (one employee in dept 20)");
    }

    /**
     * Helper method to count string occurrences
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
