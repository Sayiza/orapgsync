package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for Oracle string aggregation (LISTAGG, WM_CONCAT) → PostgreSQL STRING_AGG.
 *
 * <p>String comparison alone cannot catch the two ways this mapping goes wrong, and both are
 * silent: PostgreSQL's default window frame turns the analytic form into a <i>running</i>
 * concatenation, and a missing {@code ::text} cast fails overload resolution rather than
 * coercing. Both are pinned here by running the transformed SQL and reading the rows back.
 *
 * <p>Data is chosen so the expected order differs from insertion order and from the physical
 * order, so a dropped ORDER BY cannot pass by accident.
 */
public class PostgresListaggValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("""
            CREATE TABLE hr.emp (
                empno   NUMERIC PRIMARY KEY,
                ename   TEXT,
                sal     NUMERIC,
                dept_id INT
            )
            """);
        // Insertion order deliberately differs from every order asserted below.
        executeUpdate("INSERT INTO hr.emp VALUES (7, 'SMITH', 100, 10)");
        executeUpdate("INSERT INTO hr.emp VALUES (1, 'ALLEN', 300, 10)");
        executeUpdate("INSERT INTO hr.emp VALUES (3, 'BAKER', 200, 10)");
        executeUpdate("INSERT INTO hr.emp VALUES (9, 'ZED',    50, 20)");
    }

    // ========== AGGREGATE FORM ==========

    @Test
    void listaggConcatenatesInWithinGroupOrder() throws SQLException {
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT dept_id, LISTAGG(ename, ',') WITHIN GROUP (ORDER BY sal DESC) AS names "
                        + "FROM emp GROUP BY dept_id ORDER BY dept_id");

        assertEquals(2, rows.size());
        assertEquals("ALLEN,BAKER,SMITH", rows.get(0).get("names"));
        assertEquals("ZED", rows.get(1).get("names"));
    }

    @Test
    void listaggOverANumericMeasureResolvesTheOverload() throws SQLException {
        // Without the ::text cast PostgreSQL fails with
        // "function string_agg(numeric, unknown) does not exist" - it has no implicit coercion
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT LISTAGG(empno, '-') WITHIN GROUP (ORDER BY empno) AS ids FROM emp");

        assertEquals("1-3-7-9", rows.get(0).get("ids"));
    }

    @Test
    void listaggWithoutDelimiterConcatenatesDirectly() throws SQLException {
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT LISTAGG(ename) WITHIN GROUP (ORDER BY ename) AS names "
                        + "FROM emp WHERE dept_id = 10");

        assertEquals("ALLENBAKERSMITH", rows.get(0).get("names"));
    }

    @Test
    void listaggSkipsNullsAndReturnsNullForAnEmptyGroup() throws SQLException {
        executeUpdate("INSERT INTO hr.emp VALUES (11, NULL, 10, 20)");

        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT dept_id, LISTAGG(ename, ',') WITHIN GROUP (ORDER BY ename) AS names "
                        + "FROM emp GROUP BY dept_id ORDER BY dept_id");

        // Oracle and PostgreSQL agree: NULL measures are skipped, not rendered
        assertEquals("ZED", rows.get(1).get("names"));

        List<Map<String, Object>> empty = transformAndQuery(
                "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY ename) AS names "
                        + "FROM emp WHERE dept_id = 99");
        assertNull(empty.get(0).get("names"));
    }

    @Test
    void listaggDescendingOrderPutsNullsFirstAsOracleDoes() throws SQLException {
        executeUpdate("INSERT INTO hr.emp VALUES (11, 'NOSAL', NULL, 10)");

        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY sal DESC) AS names "
                        + "FROM emp WHERE dept_id = 10");

        // Oracle's DESC sorts NULLs first; PostgreSQL's sorts them last unless told otherwise
        assertEquals("NOSAL,ALLEN,BAKER,SMITH", rows.get(0).get("names"));
    }

    // ========== ANALYTIC FORM ==========

    @Test
    void listaggAnalyticGivesEveryRowTheWholePartition() throws SQLException {
        // The regression this guards: PostgreSQL's default frame is RANGE UNBOUNDED PRECEDING
        // AND CURRENT ROW, which would yield ALLEN / ALLEN,BAKER / ALLEN,BAKER,SMITH - a
        // running concatenation that reports success and silently corrupts the view.
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT ename, LISTAGG(ename, ',') WITHIN GROUP (ORDER BY sal DESC) "
                        + "OVER (PARTITION BY dept_id) AS names FROM emp ORDER BY ename");

        assertEquals(4, rows.size());
        assertEquals("ALLEN,BAKER,SMITH", rows.get(0).get("names"), "ALLEN row");
        assertEquals("ALLEN,BAKER,SMITH", rows.get(1).get("names"), "BAKER row");
        assertEquals("ALLEN,BAKER,SMITH", rows.get(2).get("names"), "SMITH row");
        assertEquals("ZED", rows.get(3).get("names"), "ZED row - its own partition");
    }

    @Test
    void listaggAnalyticWithoutPartitionSpansEveryRow() throws SQLException {
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY sal DESC) OVER () AS names "
                        + "FROM emp");

        assertEquals(4, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals("ALLEN,BAKER,SMITH,ZED", row.get("names"));
        }
    }

    // ========== DISTINCT ==========

    @Test
    void listaggDistinctRemovesDuplicates() throws SQLException {
        executeUpdate("INSERT INTO hr.emp VALUES (13, 'ALLEN', 400, 10)");

        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT LISTAGG(DISTINCT ename, ',') WITHIN GROUP (ORDER BY ename) AS names "
                        + "FROM emp WHERE dept_id = 10");

        assertEquals("ALLEN,BAKER,SMITH", rows.get(0).get("names"));
    }

    // ========== ON OVERFLOW ==========

    @Test
    void listaggOnOverflowErrorRunsUnbounded() throws SQLException {
        // Oracle would raise ORA-01489 past 4000 bytes; PostgreSQL text has no such limit, so
        // dropping the clause is a relaxation rather than a change of result
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT LISTAGG(ename, ',' ON OVERFLOW ERROR) WITHIN GROUP (ORDER BY ename) "
                        + "AS names FROM emp WHERE dept_id = 10");

        assertEquals("ALLEN,BAKER,SMITH", rows.get(0).get("names"));
    }

    // ========== WM_CONCAT ==========

    @Test
    void wmConcatAggregatesWithACommaSeparator() throws SQLException {
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT WM_CONCAT(ename) AS names FROM emp WHERE dept_id = 20");

        assertEquals("ZED", rows.get(0).get("names"));
    }

    @Test
    void wmConcatOverANumericColumnResolvesTheOverload() throws SQLException {
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT WM_CONCAT(empno) AS ids FROM emp WHERE dept_id = 20");

        assertEquals("9", rows.get(0).get("ids"));
    }

    // ========== HELPERS ==========

    private List<Map<String, Object>> transformAndQuery(String oracleSql) throws SQLException {
        TransformationResult result = transformSql(oracleSql, "hr");
        assertTrue(result.isSuccess(),
                "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("Oracle:     " + oracleSql);
        System.out.println("PostgreSQL: " + result.getPostgresSql());

        return executeQuery(result.getPostgresSql());
    }
}
