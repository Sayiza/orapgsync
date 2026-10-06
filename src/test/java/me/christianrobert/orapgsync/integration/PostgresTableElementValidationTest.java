package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for the {@code table_element} grammar rule.
 *
 * <p>{@code table_element} is the alternative the grammar names <em>first</em> for the argument of
 * {@code TO_CHAR}, {@code TO_DATE} and {@code COALESCE}, so a bare identifier written there never
 * reaches {@code VisitGeneralElement} — where every other identifier in an expression is resolved.
 * Two defects lived in that gap, and only running the SQL distinguishes them:
 *
 * <ul>
 *   <li>{@code TO_CHAR(SYSDATE, 'YYYY')} failed loudly with <em>column "sysdate" does not
 *       exist</em>.</li>
 *   <li>{@code COALESCE(ename, 'UNKNOWN')} failed <strong>silently</strong> — the default was
 *       emitted as the literal {@code null}, so the view compiled, ran, and returned NULL for every
 *       row Oracle would have defaulted. A string-comparison test can pin the output, but only
 *       execution shows the value is wrong.</li>
 * </ul>
 */
public class PostgresTableElementValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    @Override
    void setup() throws SQLException {
        super.setup();

        executeBatch("""
            CREATE SCHEMA hr;
            CREATE TABLE hr.employees (
                ename       text,
                comm        numeric,
                hire_date   timestamp
            );
            INSERT INTO hr.employees VALUES (NULL, NULL, TIMESTAMP '2020-01-15 09:30:00');
            """);
    }

    private Object firstValue(String oracleSql) throws SQLException {
        TransformationResult result = transformSql(oracleSql, "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());
        assertEquals(1, rows.size(), "Expected exactly one row from: " + result.getPostgresSql());
        return rows.get(0).values().iterator().next();
    }

    // ========== PSEUDO-COLUMNS ==========

    /**
     * Compared against the database's own clock rather than a hard-coded year, so the test does not
     * expire.
     */
    @Test
    void sysdateWithFormatExecutes() throws SQLException {
        Object expected = executeQuery("SELECT to_char(CURRENT_TIMESTAMP, 'YYYY') AS v")
            .get(0).get("v");

        assertEquals(expected, firstValue("SELECT TO_CHAR(SYSDATE, 'YYYY') AS v FROM dual"));
    }

    @Test
    void systimestampWithFormatExecutes() throws SQLException {
        Object expected = executeQuery("SELECT to_char(CURRENT_TIMESTAMP, 'YYYY') AS v")
            .get(0).get("v");

        assertEquals(expected, firstValue("SELECT TO_CHAR(SYSTIMESTAMP, 'YYYY') AS v FROM dual"));
    }

    @Test
    void sysdateInToDateExecutes() throws SQLException {
        assertNotNull(firstValue("SELECT TO_DATE(SYSDATE, 'YYYY') AS v FROM dual"));
    }

    // ========== COALESCE DEFAULT VALUE ==========

    /**
     * The silent one. {@code ename} is NULL in the fixture, so a lost default is visible as NULL
     * rather than as a compile error.
     */
    @Test
    void coalesceStringDefaultIsReturned() throws SQLException {
        assertEquals("UNKNOWN",
            firstValue("SELECT COALESCE(ename, 'UNKNOWN') AS v FROM employees"));
    }

    @Test
    void coalesceNumericDefaultIsReturned() throws SQLException {
        assertEquals(0, ((Number) firstValue("SELECT COALESCE(comm, 0) AS v FROM employees")).intValue());
    }

    // ========== IDENTIFIERS ==========

    /**
     * A delimited identifier used to reach PostgreSQL quoted and case-sensitive, missing the
     * migrated lower-case column.
     */
    @Test
    void quotedIdentifierResolvesToMigratedColumn() throws SQLException {
        assertEquals("2020",
            firstValue("SELECT TO_CHAR(\"HIRE_DATE\", 'YYYY') AS v FROM employees"));
    }

    @Test
    void aliasQualifiedColumnStillResolves() throws SQLException {
        assertEquals("2020",
            firstValue("SELECT TO_CHAR(e.hire_date, 'YYYY') AS v FROM employees e"));
    }
}
