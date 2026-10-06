package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.oraclecompat.implementations.BitandImpl;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution tests for Oracle {@code BITAND} → {@code oracle_compat.bitand}.
 *
 * <p>These run the emitted SQL against a real PostgreSQL rather than comparing strings, because the
 * decisions this mapping encodes are all invisible in the generated text:
 *
 * <ul>
 *   <li>PostgreSQL's {@code &} has no {@code numeric} overload, so the wrong emission fails at
 *       execution, not at transformation;</li>
 *   <li>Oracle truncates its arguments toward zero while a bare cast rounds — a wrong answer, not
 *       an error;</li>
 *   <li>the function returns {@code numeric} rather than {@code bigint} specifically so that
 *       division downstream of a call does not silently become integer division;</li>
 *   <li>and the function must be <em>inlined</em> by the planner, which no string test can see.</li>
 * </ul>
 *
 * @see BitandImpl
 */
public class PostgresBitandValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        executeUpdate("CREATE SCHEMA hr");
        executeUpdate("CREATE SCHEMA oracle_compat");

        // Install the compat function exactly as the installation job would
        executeUpdate(BitandImpl.getBitand());

        executeUpdate("""
            CREATE TABLE hr.perms (
                id INT PRIMARY KEY,
                flags NUMERIC,
                required_mask NUMERIC
            )
            """);
        executeUpdate("INSERT INTO hr.perms VALUES (1, 7, 4)");   // 0111 & 0100 = 4 → match
        executeUpdate("INSERT INTO hr.perms VALUES (2, 3, 4)");   // 0011 & 0100 = 0 → no match
        executeUpdate("INSERT INTO hr.perms VALUES (3, 12, 4)");  // 1100 & 0100 = 4 → match
    }

    /** Reads an EXPLAIN plan back as one string. */
    private String explain(String sql) throws SQLException {
        return executeQuery("EXPLAIN (VERBOSE, COSTS OFF) " + sql).stream()
                .map(row -> String.valueOf(row.values().iterator().next()))
                .collect(Collectors.joining("\n"));
    }

    @Test
    void bitandInSelectListReturnsOracleValues() throws SQLException {
        TransformationResult result = transformSql(
                "SELECT id, BITAND(flags, required_mask) AS masked FROM perms ORDER BY id", "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed");

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertEquals(3, rows.size());
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) rows.get(0).get("masked")));
        assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) rows.get(1).get("masked")));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) rows.get(2).get("masked")));
    }

    /**
     * The predicate form, which is where BITAND actually appears in this migration —
     * {@code BITAND(mask, bit) = bit} as a "has this flag" test.
     */
    @Test
    void bitandInWhereClauseFiltersCorrectly() throws SQLException {
        TransformationResult result = transformSql(
                "SELECT id FROM perms WHERE BITAND(flags, required_mask) = required_mask ORDER BY id",
                "hr");
        assertTrue(result.isSuccess(), "Transformation should succeed");

        List<Map<String, Object>> rows = executeQuery(result.getPostgresSql());

        assertEquals(2, rows.size(), "Rows 1 and 3 have the bit set");
        assertEquals(1, rows.get(0).get("id"));
        assertEquals(3, rows.get(1).get("id"));
    }

    /**
     * Oracle truncates a non-integer argument toward zero; PostgreSQL's numeric→bigint cast rounds
     * half away from zero. {@code BITAND(1.7, 3)} is 1 in Oracle and would be 2 through a bare cast.
     */
    @Test
    void argumentsAreTruncatedTowardZeroNotRounded() throws SQLException {
        List<Map<String, Object>> rows = executeQuery(
                "SELECT oracle_compat.bitand(1.7, 3) AS a, oracle_compat.bitand(-1.7, -3) AS b");

        assertEquals(0, new BigDecimal("1").compareTo((BigDecimal) rows.get(0).get("a")),
                "BITAND(1.7, 3) must truncate to BITAND(1, 3) = 1, not round to BITAND(2, 3) = 2");
        assertEquals(0, new BigDecimal("-3").compareTo((BigDecimal) rows.get(0).get("b")),
                "BITAND(-1.7, -3) must truncate toward zero to BITAND(-1, -3) = -3");
    }

    /**
     * The return type is {@code numeric}, not {@code bigint}. With a bigint return this yields 1 —
     * PostgreSQL integer division — where Oracle yields 1.5.
     */
    @Test
    void returnTypeKeepsDivisionFractional() throws SQLException {
        List<Map<String, Object>> rows = executeQuery(
                "SELECT oracle_compat.bitand(7, 3) / 2 AS half");

        assertEquals(0, new BigDecimal("1.5").compareTo((BigDecimal) rows.get(0).get("half")),
                "Division after BITAND must stay fractional, as it is in Oracle");
    }

    @Test
    void nullArgumentYieldsNull() throws SQLException {
        List<Map<String, Object>> rows = executeQuery(
                "SELECT oracle_compat.bitand(NULL, 4) AS a, oracle_compat.bitand(4, NULL) AS b");

        assertNull(rows.get(0).get("a"), "NULL propagates, as in Oracle");
        assertNull(rows.get(0).get("b"), "NULL propagates, as in Oracle");
    }

    /**
     * A mask beyond the bigint range fails loudly rather than silently losing bits. This is the one
     * documented deviation from Oracle, and it is deliberate — see {@link BitandImpl}.
     */
    @Test
    void maskBeyondBigintRangeFailsLoudly() {
        SQLException ex = assertThrows(SQLException.class,
                () -> executeQuery("SELECT oracle_compat.bitand(power(2::numeric, 70::numeric), 4)"));

        assertTrue(ex.getMessage().toLowerCase().contains("out of range"),
                "Should fail with bigint out of range, not return a wrong answer: " + ex.getMessage());
    }

    /**
     * <strong>The performance property, and it is load-bearing.</strong> PostgreSQL inlines a
     * single-expression SQL function at plan time, so the call vanishes and costs what the
     * hand-written expression costs. Rewriting the body in PL/pgSQL, or dropping IMMUTABLE, removes
     * that silently — measured at 3.5x slower over 5M rows. The plan is the only place it shows.
     */
    @Test
    void compatFunctionIsInlinedByThePlanner() throws SQLException {
        String plan = explain(
                "SELECT id FROM hr.perms WHERE oracle_compat.bitand(flags, 4) = 4");

        assertFalse(plan.toLowerCase().contains("bitand"),
                "oracle_compat.bitand must be inlined away at plan time; plan was:\n" + plan);
        assertTrue(plan.contains("&"),
                "The inlined plan should contain the bitwise operator; plan was:\n" + plan);
    }

    /**
     * IMMUTABLE is what makes an expression index on the call legal, and the planner inlines the
     * index expression too, so such an index is matched by the equivalent query.
     */
    @Test
    void expressionIndexOnBitandIsUsable() throws SQLException {
        executeUpdate("CREATE INDEX perms_bitand4 ON hr.perms ((oracle_compat.bitand(flags, 4)))");
        executeUpdate("SET enable_seqscan = off");

        String plan = explain(
                "SELECT id FROM hr.perms WHERE oracle_compat.bitand(flags, 4) = 4");

        assertTrue(plan.contains("perms_bitand4"),
                "Expression index should be matched; plan was:\n" + plan);
    }

    /**
     * A PL/pgSQL body resolves its calls at call time, so creating the function proves nothing —
     * this calls it. That is exactly the failure mode BITAND had before this mapping: 19 function
     * bodies in the 2026-09-01 run were reported implemented while calling a nonexistent
     * {@code schema.bitand}.
     */
    @Test
    void bitandInsideFunctionBodyResolvesWhenCalled() throws SQLException {
        String oracle = """
            FUNCTION has_flag(p_flags NUMBER, p_bit NUMBER) RETURN NUMBER IS
            BEGIN
              IF BITAND(p_flags, p_bit) = p_bit THEN
                RETURN 1;
              END IF;
              RETURN 0;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(oracle, "hr", indices);
        assertTrue(result.isSuccess(), "Transformation should succeed");

        executeUpdate(result.getPostgresSql());

        List<Map<String, Object>> rows = executeQuery(
                "SELECT hr.has_flag(7, 4) AS yes, hr.has_flag(3, 4) AS no");

        assertEquals(0, new BigDecimal("1").compareTo((BigDecimal) rows.get(0).get("yes")));
        assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) rows.get(0).get("no")));
    }
}
