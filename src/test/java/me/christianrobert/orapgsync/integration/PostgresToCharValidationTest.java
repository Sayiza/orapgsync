package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.oraclecompat.catalog.OracleBuiltinCatalog;
import me.christianrobert.orapgsync.oraclecompat.model.OracleBuiltinFunction;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for single-argument {@code TO_CHAR(expr)}.
 *
 * <p><strong>Why these exist:</strong> the transformation used to emit Oracle's bare
 * {@code TO_CHAR(x)} verbatim, and a string-comparison test asserted that as correct. PostgreSQL
 * has no one-argument {@code to_char} at all, so every such view failed at {@code CREATE VIEW} with
 * <em>"function to_char(numeric) does not exist"</em>. Only running the output catches that class
 * of bug — hence this class, alongside the string assertions in {@code ToCharTransformationTest}.
 *
 * <p>Each test transforms Oracle SQL and executes the result, so a regression to bare
 * {@code to_char} fails here on the SQL error rather than on a string mismatch.
 */
public class PostgresToCharValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    @Override
    void setup() throws SQLException {
        super.setup();

        installToCharFunctions();

        executeBatch("""
            CREATE SCHEMA hr;
            CREATE TABLE hr.employees (
                empno       numeric,
                salary      numeric(10,2),
                ratio       numeric,
                hire_date   timestamp,
                ename       text
            );
            INSERT INTO hr.employees VALUES (7369, 1250.50, 0.5, TIMESTAMP '2020-01-15 09:30:00', 'SMITH');
            """);
    }

    /**
     * Installs only the TO_CHAR overloads, with the catalog's default NLS settings
     * (NLS_DATE_FORMAT = {@code DD-MON-RR}).
     */
    private void installToCharFunctions() throws SQLException {
        executeUpdate("CREATE SCHEMA IF NOT EXISTS oracle_compat");

        OracleBuiltinCatalog catalog = new OracleBuiltinCatalog();
        for (OracleBuiltinFunction func : catalog.getAllFunctions()) {
            if ("TO_CHAR".equals(func.getFunctionName()) && func.getSqlDefinition() != null) {
                executeUpdate(func.getSqlDefinition());
            }
        }
    }

    private Object firstValue(String oracleSql) throws SQLException {
        TransformationResult result = transformSql(oracleSql, "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());
        assertEquals(1, rows.size(), "Expected exactly one row from: " + result.getPostgresSql());
        return rows.get(0).values().iterator().next();
    }

    // ========== NUMERIC ==========

    /**
     * The exact failure that started this: a bare TO_CHAR over a numeric column.
     */
    @Test
    void bareToCharOnNumericColumnExecutes() throws SQLException {
        assertEquals("7369", firstValue("SELECT TO_CHAR(empno) AS v FROM employees"));
    }

    /**
     * Oracle NUMBER carries no scale, so TO_CHAR(1250.50) is '1250.5'. PostgreSQL numeric(10,2)
     * does carry scale and renders '1250.50' — a plain {@code ::text} cast would differ here
     * silently.
     */
    @Test
    void trailingZeroesAreDroppedAsOracleDoes() throws SQLException {
        assertEquals("1250.5", firstValue("SELECT TO_CHAR(salary) AS v FROM employees"));
    }

    /**
     * Oracle's default number format omits the leading zero below 1: TO_CHAR(0.5) is '.5'.
     */
    @Test
    void leadingZeroBelowOneIsOmittedAsOracleDoes() throws SQLException {
        assertEquals(".5", firstValue("SELECT TO_CHAR(ratio) AS v FROM employees"));
    }

    /**
     * The minus sign must survive the leading-zero strip, and a whole number must keep its zero.
     */
    @Test
    void negativeFractionKeepsSignAndZeroIsUnchanged() throws SQLException {
        assertEquals("-.25", firstValue("SELECT TO_CHAR(-0.25) AS v FROM dual"));
        assertEquals("0", firstValue("SELECT TO_CHAR(0.00) AS v FROM dual"));
        assertEquals("10.5", firstValue("SELECT TO_CHAR(10.50) AS v FROM dual"));
    }

    // ========== TEMPORAL ==========

    /**
     * A bare date is not a cast either: Oracle uses NLS_DATE_FORMAT (default DD-MON-RR), where
     * PostgreSQL's timestamp::text would give '2020-01-15 09:30:00'.
     */
    @Test
    void bareToCharOnDateUsesNlsDateFormat() throws SQLException {
        assertEquals("15-JAN-20", firstValue("SELECT TO_CHAR(hire_date) AS v FROM employees"));
    }

    /**
     * SYSDATE transforms to CURRENT_TIMESTAMP, which is timestamptz — only assignment-coercible to
     * timestamp, so it would not reach the timestamp overload and needs one of its own.
     *
     * <p>Doubles as the execution proof for the {@code table_element} fix: a bare
     * {@code TO_CHAR(SYSDATE)} used to emit the pseudo-column as the column {@code sysdate} and fail
     * on "column sysdate does not exist", because the grammar matches the argument as
     * {@code table_element} before {@code expression} and that path did not know the pseudo-columns.
     */
    @Test
    void bareToCharOnSysdateExecutes() throws SQLException {
        Object value = firstValue("SELECT TO_CHAR(SYSDATE) AS v FROM dual");
        assertNotNull(value);
        assertTrue(value.toString().matches("\\d{2}-[A-Z]{3}-\\d{2}"),
            "SYSDATE should render in NLS_DATE_FORMAT, was: " + value);
    }

    /**
     * The arithmetic form always worked — it routes through the {@code expression} branch rather
     * than {@code table_element}. Pinned so the asymmetry that hid the pseudo-column bug stays
     * visible.
     */
    @Test
    void toCharOnDateArithmeticExecutes() throws SQLException {
        Object value = firstValue("SELECT TO_CHAR(SYSDATE + 1) AS v FROM dual");
        assertNotNull(value);
        assertTrue(value.toString().matches("\\d{2}-[A-Z]{3}-\\d{2}"), "was: " + value);
    }

    // ========== OVERLOAD RESOLUTION ==========

    /**
     * An untyped literal must not fail as ambiguous across the overload set. PostgreSQL resolves an
     * unknown argument to the string category's preferred type, which is text — so the text
     * overload wins uniquely.
     */
    @Test
    void untypedLiteralResolvesToTextOverload() throws SQLException {
        assertEquals("abc", firstValue("SELECT TO_CHAR('abc') AS v FROM dual"));
    }

    @Test
    void textColumnPassesThroughUnchanged() throws SQLException {
        assertEquals("SMITH", firstValue("SELECT TO_CHAR(ename) AS v FROM employees"));
    }

    // ========== TWO-ARGUMENT FORM IS UNAFFECTED ==========

    /**
     * The two-argument form maps straight to the PostgreSQL built-in and must not be routed through
     * the compatibility layer — pinned here because the compat overloads take no format argument,
     * so a mistaken route would fail loudly.
     */
    @Test
    void twoArgumentFormStillUsesPostgresBuiltin() throws SQLException {
        assertEquals("2020-01-15",
            firstValue("SELECT TO_CHAR(hire_date, 'YYYY-MM-DD') AS v FROM employees"));
    }
}
