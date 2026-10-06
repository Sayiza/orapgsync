package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for TO_NUMBER / TO_TIMESTAMP against a real PostgreSQL.
 *
 * <p>These exist because the single-argument form is exactly the case that a string-comparison
 * test cannot catch: {@code TO_NUMBER(x)} transforms to something that <em>looks</em> plausible
 * either way, but PostgreSQL has no single-argument {@code to_number} and would only reject the
 * naive pass-through at {@code CREATE VIEW} time — i.e. during a real migration run.
 */
class PostgresConversionFunctionValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupOrderData() throws SQLException {
        executeUpdate("""
            CREATE SCHEMA hr;
            CREATE TABLE hr.orders (
                order_id INT PRIMARY KEY,
                order_no VARCHAR(20),
                amount VARCHAR(20),
                created VARCHAR(20)
            );

            INSERT INTO hr.orders (order_id, order_no, amount, created) VALUES
                (1, '100',  '42.50',  '2026-01-15'),
                (2, '205',  '7.25',   '2026-02-28'),
                (3, '3080', '150.00', '2026-03-01');
            """);
    }

    /**
     * Single-argument TO_NUMBER: numeric conversion, arithmetic on the result, and use in a
     * WHERE predicate all in one query — the shape that actually appears in the migrated views.
     */
    @Test
    void singleArgumentToNumberConvertsFiltersAndComputes() throws SQLException {
        String oracleSql = """
            SELECT order_id,
                   TO_NUMBER(order_no) AS num,
                   TO_NUMBER(amount) * 2 AS double_amount
              FROM orders
             WHERE TO_NUMBER(order_no) > 150
             ORDER BY TO_NUMBER(order_no)
            """;

        TransformationResult result = transformSql(oracleSql, "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertRowCount(2, rows);
        assertColumnValue(rows, 0, "num", new BigDecimal("205"));
        assertColumnValue(rows, 0, "double_amount", new BigDecimal("14.50"));
        assertColumnValue(rows, 1, "num", new BigDecimal("3080"));
        assertColumnValue(rows, 1, "double_amount", new BigDecimal("300.00"));
    }

    /**
     * Two-argument TO_NUMBER: verifies the {@code ::text} cast on the value is present and
     * correct — PostgreSQL's {@code to_number(text, text)} has no numeric-input overload, so
     * omitting it produces "function to_number(numeric, unknown) does not exist".
     */
    @Test
    void toNumberWithFormatModelExecutes() throws SQLException {
        TransformationResult result = transformSql(
            "SELECT TO_NUMBER(amount, '9999D99') AS parsed FROM orders WHERE order_id = 1", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertRowCount(1, rows);
        assertEquals(0, new BigDecimal("42.50").compareTo(
            getColumnValue(rows, 0, "parsed", BigDecimal.class)));
    }

    /**
     * Single-argument TO_TIMESTAMP, plus a check that the result is a real timestamp and not
     * text — EXTRACT would fail on the latter.
     */
    @Test
    void singleArgumentToTimestampProducesUsableTimestamp() throws SQLException {
        TransformationResult result = transformSql(
            "SELECT EXTRACT(MONTH FROM TO_TIMESTAMP(created)) AS mon "
            + "FROM orders WHERE order_id = 2", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertRowCount(1, rows);
        assertEquals(0, new BigDecimal("2").compareTo(
            getColumnValue(rows, 0, "mon", BigDecimal.class)));
    }

    /**
     * Single-argument TO_DATE. This is the case where the old {@code TO_TIMESTAMP(x)} output was
     * not merely wrong but dangerous: PostgreSQL resolves a one-argument {@code to_timestamp} to
     * the {@code double precision} overload, which reads its argument as Unix epoch seconds. For
     * a text argument that is a hard error; for a numeric one it silently returns a 1970 date.
     */
    @Test
    void singleArgumentToDateProducesTheActualDate() throws SQLException {
        TransformationResult result = transformSql(
            "SELECT TO_DATE(created) AS d FROM orders WHERE order_id = 3", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertRowCount(1, rows);
        assertEquals(java.sql.Timestamp.valueOf("2026-03-01 00:00:00"),
            getColumnValue(rows, 0, "d", java.sql.Timestamp.class));
    }

    /**
     * The epoch trap made concrete: a numeric argument must not come back as a 1970 timestamp.
     */
    @Test
    void singleArgumentToDateOnNumericInputIsNotReadAsUnixEpoch() throws SQLException {
        executeUpdate("CREATE TABLE hr.stamps (id INT, ts_value NUMERIC)");
        executeUpdate("INSERT INTO hr.stamps VALUES (1, 20260301)");

        TransformationResult result = transformSql("SELECT TO_DATE(ts_value) AS d FROM stamps", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        // to_timestamp(20260301::double precision) would silently yield 1970-08-23
        SQLException ex = assertThrows(SQLException.class,
            () -> executeQuery(result.getPostgresSql()));
        assertFalse(ex.getMessage().contains("1970"), "Sanity: failure should be a cast error");
    }

    /**
     * Two-argument TO_DATE over a NUMERIC column — the Oracle idiom of storing dates as numbers.
     * Without the {@code ::text} cast this fails with "function to_timestamp(numeric, unknown)
     * does not exist"; with it, the date parses correctly.
     */
    @Test
    void toDateWithFormatOverNumericColumnParsesTheDate() throws SQLException {
        executeUpdate("CREATE TABLE hr.stamps (id INT, ymd NUMERIC)");
        executeUpdate("INSERT INTO hr.stamps VALUES (1, 20260301)");

        TransformationResult result = transformSql(
            "SELECT TO_DATE(ymd, 'YYYYMMDD') AS d FROM stamps", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertRowCount(1, rows);
        assertEquals(java.sql.Timestamp.valueOf("2026-03-01 00:00:00"),
            getColumnValue(rows, 0, "d", java.sql.Timestamp.class));
    }

    /**
     * The transformed SQL must survive CREATE VIEW, which is where a migration run would hit it.
     */
    @Test
    void transformedToNumberIsAcceptedByCreateView() throws SQLException {
        TransformationResult result = transformSql(
            "SELECT order_id, TO_NUMBER(order_no) AS num FROM orders", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        executeUpdate("CREATE VIEW hr.v_orders AS " + result.getPostgresSql());

        List<Map<String, Object>> rows = executeQuery("SELECT num FROM hr.v_orders ORDER BY order_id");
        assertRowCount(3, rows);
        assertColumnValue(rows, 0, "num", new BigDecimal("100"));
    }
}
