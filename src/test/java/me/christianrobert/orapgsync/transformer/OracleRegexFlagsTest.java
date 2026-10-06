package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.functions.OracleRegexFlags;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Oracle {@code match_param} → PostgreSQL regex flags mapping.
 *
 * <p>The expected values were verified against PostgreSQL 17 with the subject
 * {@code E'ab\ncd'}: pattern {@code 'b.c'} matches only when {@code .} may cross a newline,
 * and pattern {@code '^cd'} matches only when {@code ^} anchors per line.
 */
public class OracleRegexFlagsTest {

    // ==================== Newline handling ====================

    @Test
    void oracleDefaultBecomesPartialNewlineSensitive() {
        // Oracle's default (. does not match newline, whole-string anchors) is PostgreSQL's 'p'.
        // PostgreSQL's own default is different, so an empty flag string must not stay empty.
        assertEquals("p", OracleRegexFlags.toPostgres(null));
        assertEquals("p", OracleRegexFlags.toPostgres(""));
    }

    @Test
    void oracleNMeansPostgresDefault() {
        // Oracle 'n' lets . match newline, which is what PostgreSQL does with no flag at all.
        assertEquals("", OracleRegexFlags.toPostgres("n"));
    }

    @Test
    void oracleMBecomesNewlineSensitive() {
        // Oracle 'm' gives per-line anchors while . still does not match newline → PostgreSQL 'n'.
        assertEquals("n", OracleRegexFlags.toPostgres("m"));
    }

    @Test
    void oracleMAndNBecomeWeird() {
        // Both dimensions on → PostgreSQL's "inverse partial newline-sensitive" flag.
        assertEquals("w", OracleRegexFlags.toPostgres("mn"));
        assertEquals("w", OracleRegexFlags.toPostgres("nm"));
    }

    // ==================== Pass-through flags ====================

    @Test
    void caseAndWhitespaceFlagsPassThrough() {
        assertEquals("ip", OracleRegexFlags.toPostgres("i"));
        assertEquals("cp", OracleRegexFlags.toPostgres("c"));
        assertEquals("xp", OracleRegexFlags.toPostgres("x"));
    }

    @Test
    void passThroughFlagsKeepSourceOrder() {
        // Both engines let the last of two contradictory flags win, so order must be preserved.
        assertEquals("icp", OracleRegexFlags.toPostgres("ic"));
        assertEquals("cip", OracleRegexFlags.toPostgres("ci"));
    }

    @Test
    void passThroughCombinesWithNewlineMapping() {
        assertEquals("i", OracleRegexFlags.toPostgres("in"));
        assertEquals("in", OracleRegexFlags.toPostgres("im"));
        assertEquals("iw", OracleRegexFlags.toPostgres("imn"));
    }

    // ==================== Validation ====================

    @Test
    void unknownFlagIsRejected() {
        // Oracle rejects these too; silently dropping one would change matching semantics.
        TransformationException exception = assertThrows(TransformationException.class,
            () -> OracleRegexFlags.toPostgres("g"));

        assertTrue(exception.getMessage().contains("'g'"),
            "Exception should name the offending character: " + exception.getMessage());
    }
}
