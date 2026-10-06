package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for {@code CAST(expr AS type)} against a real PostgreSQL.
 *
 * <p>The string-comparison tests in {@code CastTransformationTest} pin the emitted SQL; these pin
 * the values that SQL produces, which is where the two engines were at risk of disagreeing
 * silently: the {@code AS DATE} time component, and the precision a naive mapping would drop.</p>
 */
public class PostgresCastValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("""
            CREATE TABLE hr.events (
                id        INT PRIMARY KEY,
                created   TIMESTAMP,
                amount    NUMERIC,
                big_n     NUMERIC,
                label     TEXT
            )
            """);
        // 12:34:56.789 - a fractional second that both a truncation and a rounding would change,
        // in opposite directions.
        executeUpdate("""
            INSERT INTO hr.events VALUES
                (1, TIMESTAMP '2026-03-14 12:34:56.789', 123.456, 3000000000, 'abcdefghij')
            """);
    }

    private List<Map<String, Object>> runOracle(String oracleSql) throws SQLException {
        TransformationResult result = transformSql(oracleSql, "hr");
        assertTrue(result.isSuccess(),
            "Transformation should succeed: " + result.getErrorMessage());
        System.out.println("=== " + oracleSql + "\n--> " + result.getPostgresSql());
        return executeQuery(result.getPostgresSql());
    }

    @Test
    void castToDateKeepsTheTimeAndDropsOnlyTheFractionalSeconds() throws SQLException {
        // Oracle DATE is a day plus a time to the second. The whole point of the mapping is that
        // 12:34:56 survives - PostgreSQL's own 'date' would leave 00:00:00 here.
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(created AS DATE) AS d FROM events");

        assertEquals(1, rows.size());
        assertEquals(Timestamp.valueOf("2026-03-14 12:34:56.0"), rows.get(0).get("d"));
    }

    @Test
    void castToDateTruncatesWhereTimestampZeroWouldRound() throws SQLException {
        // .789 rounds up to :57 under ::timestamp(0) and truncates to :56 under Oracle. This is
        // the assertion that makes ::timestamp(0) a wrong answer rather than a tidier one.
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(created AS DATE) AS d FROM events");
        Timestamp viaCast = (Timestamp) rows.get(0).get("d");

        Timestamp viaRounding = (Timestamp) executeQuery(
            "SELECT (created)::timestamp(0) AS d FROM hr.events").get(0).get("d");

        assertEquals(Timestamp.valueOf("2026-03-14 12:34:56.0"), viaCast);
        assertEquals(Timestamp.valueOf("2026-03-14 12:34:57.0"), viaRounding);
        assertNotEquals(viaRounding, viaCast);
    }

    @Test
    void castToNumberAppliesTheScale() throws SQLException {
        // Dropping the precision - as TypeConverter would - leaves 123.456 unrounded
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(amount AS NUMBER(10,2)) AS a FROM events");

        assertEquals(0, new BigDecimal("123.46").compareTo((BigDecimal) rows.get(0).get("a")));
    }

    @Test
    void castToNumberWithNegativeScaleRoundsLeftOfThePoint() throws SQLException {
        // Requires PostgreSQL 15+, which the project already assumes for the REGEXP family
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(amount AS NUMBER(10,-2)) AS a FROM events");

        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) rows.get(0).get("a")));
    }

    @Test
    void ansiIntegerCastAcceptsValuesThatWouldOverflowInt4() throws SQLException {
        // CAST(3000000000 AS SMALLINT) is legal Oracle - both aliases are NUMBER(38,0). This is
        // the case where ::integer/::smallint would raise "out of range".
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(big_n AS INTEGER) AS n, CAST(big_n AS SMALLINT) AS s FROM events");

        assertEquals(0, new BigDecimal("3000000000").compareTo((BigDecimal) rows.get(0).get("n")));
        assertEquals(0, new BigDecimal("3000000000").compareTo((BigDecimal) rows.get(0).get("s")));
    }

    @Test
    void ansiIntegerCastRoundsRatherThanTruncates() throws SQLException {
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(amount AS INTEGER) AS n FROM events");

        assertEquals(0, new BigDecimal("123").compareTo((BigDecimal) rows.get(0).get("n")));
    }

    @Test
    void castToVarchar2AppliesTheLength() throws SQLException {
        List<Map<String, Object>> rows =
            runOracle("SELECT CAST(label AS VARCHAR2(4)) AS s FROM events");

        assertEquals("abcd", rows.get(0).get("s"));
    }

    @Test
    void castResultComparesAgainstAMigratedDateColumn() throws SQLException {
        // Oracle DATE columns migrate to timestamp, so the cast result has to be comparable to
        // one without a further cast.
        List<Map<String, Object>> rows = runOracle(
            "SELECT id FROM events WHERE CAST(created AS DATE) = TO_TIMESTAMP('2026-03-14 12:34:56')");

        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("id"));
    }
}
