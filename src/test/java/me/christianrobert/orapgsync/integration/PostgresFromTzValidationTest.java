package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for Oracle FROM_TZ → PostgreSQL AT TIME ZONE.
 *
 * <p>String comparison cannot catch how this mapping goes wrong, because the wrong form is
 * perfectly valid SQL that returns a wrong instant without complaint. Two ways:
 *
 * <ul>
 *   <li>An offset emitted as <i>text</i> is read POSIX-style by PostgreSQL, with the sign
 *       inverted - {@code AT TIME ZONE '+03:00'} means UTC-3. Six hours off, no error.</li>
 *   <li>{@code AT TIME ZONE} takes its direction from the operand's static type, so on a
 *       {@code timestamptz} argument it performs the reverse conversion.</li>
 * </ul>
 *
 * <p>Every assertion is on the resulting <i>instant</i>, which is zone-independent, and the
 * session {@code TimeZone} is pinned to UTC so a wrong instant cannot be masked by rendering.
 * Expected values are Oracle's: {@code FROM_TZ(TIMESTAMP '2000-03-28 08:00:00', '+03:00')} is
 * 08:00 in a +03:00 zone, i.e. 05:00 UTC.
 */
public class PostgresFromTzValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Pin the session zone: a timestamptz is rendered in it, and ::timestamp reads it.
        executeUpdate("SET TIME ZONE 'UTC'");
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("""
            CREATE TABLE hr.events (
                event_id   INT PRIMARY KEY,
                event_ts   TIMESTAMP,
                event_tstz TIMESTAMPTZ
            )
            """);
        // 2000-03-28 is inside Central European Summer Time (which began 2000-03-26),
        // 2000-01-15 is outside it, so a region zone has to apply daylight saving to pass.
        executeUpdate("INSERT INTO hr.events VALUES "
                + "(1, TIMESTAMP '2000-03-28 08:00:00', TIMESTAMPTZ '2000-03-28 08:00:00+00'), "
                + "(2, TIMESTAMP '2000-01-15 08:00:00', TIMESTAMPTZ '2000-01-15 08:00:00+00')");
    }

    @Test
    void fromTzWithPositiveOffsetMatchesOracle() throws SQLException {
        // The regression that matters. Emitting '+03:00' as text would give 11:00Z instead.
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT FROM_TZ(event_ts, '+03:00') AS v FROM events WHERE event_id = 1");

        assertEquals(Instant.parse("2000-03-28T05:00:00Z"), instantOf(rows.get(0).get("v")));
    }

    @Test
    void fromTzWithNegativeOffsetMatchesOracle() throws SQLException {
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT FROM_TZ(event_ts, '-05:00') AS v FROM events WHERE event_id = 1");

        assertEquals(Instant.parse("2000-03-28T13:00:00Z"), instantOf(rows.get(0).get("v")));
    }

    @Test
    void fromTzWithUnsignedOffsetMatchesOracle() throws SQLException {
        // Oracle's own documentation example writes the offset without a sign.
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT FROM_TZ(event_ts, '3:00') AS v FROM events WHERE event_id = 1");

        assertEquals(Instant.parse("2000-03-28T05:00:00Z"), instantOf(rows.get(0).get("v")));
    }

    @Test
    void fromTzWithRegionNameAppliesDaylightSaving() throws SQLException {
        // Both rows carry the same wall-clock time; only the date differs, so the two results
        // can only diverge if the region's daylight-saving rule is actually applied.
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT event_id, FROM_TZ(event_ts, 'Europe/Vienna') AS v FROM events ORDER BY event_id");

        assertEquals(Instant.parse("2000-03-28T06:00:00Z"), instantOf(rows.get(0).get("v")),
                "summer: Vienna is UTC+02:00");
        assertEquals(Instant.parse("2000-01-15T07:00:00Z"), instantOf(rows.get(1).get("v")),
                "winter: Vienna is UTC+01:00");
    }

    @Test
    void fromTzOnATimestamptzArgumentKeepsTheOracleDirection() throws SQLException {
        // Without the ::timestamp cast, AT TIME ZONE would run backwards on this argument and
        // return a zone-less timestamp - a different type and a different value.
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT FROM_TZ(event_tstz, '+03:00') AS v FROM events WHERE event_id = 1");

        assertEquals(Instant.parse("2000-03-28T05:00:00Z"), instantOf(rows.get(0).get("v")));
    }

    @Test
    void fromTzResultCanBeComparedAgainstATimestamptzColumn() throws SQLException {
        // Confirms the result really is a timestamptz: comparing it to one must not need a cast.
        List<Map<String, Object>> rows = transformAndQuery(
                "SELECT event_id FROM events WHERE FROM_TZ(event_ts, '+00:00') = event_tstz "
                        + "ORDER BY event_id");

        assertEquals(2, rows.size(), "both rows store the same wall clock at UTC");
    }

    @Test
    void fromTzWithANonLiteralZoneFailsLoudly() {
        // A column-valued zone cannot have its sign decided at transformation time, so it is
        // rejected rather than emitted as text and silently inverted.
        TransformationResult result = transformSql(
                "SELECT FROM_TZ(event_ts, tz_name) AS v FROM events", "hr");

        assertFalse(result.isSuccess(), "non-literal zone should not transform");
        assertTrue(result.getErrorMessage().contains("time_zone"),
                "error should name the time_zone argument: " + result.getErrorMessage());
    }

    /** Reads a JDBC timestamp back as an absolute instant, independent of the JVM's zone. */
    private static Instant instantOf(Object value) {
        assertNotNull(value, "expected a timestamp value");
        assertInstanceOf(Timestamp.class, value, "expected a timestamp, got: " + value.getClass());
        return ((Timestamp) value).toInstant();
    }

    private List<Map<String, Object>> transformAndQuery(String oracleSql) throws SQLException {
        TransformationResult result = transformSql(oracleSql, "hr");
        assertTrue(result.isSuccess(),
                "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("Oracle:     " + oracleSql);
        System.out.println("PostgreSQL: " + result.getPostgresSql());

        return executeQuery(result.getPostgresSql());
    }
}
