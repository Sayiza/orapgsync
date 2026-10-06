package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Oracle's row quantifier: {@code SELECT (DISTINCT | UNIQUE | ALL)? selected_list}.
 *
 * <p>The quantifier used to be dropped outright — {@code SELECT DISTINCT a FROM t} transformed to
 * {@code SELECT a FROM t}, which is valid PostgreSQL that returns a different result set. Nothing
 * failed, so the view reported success and only the row count gave it away.
 */
class RowQuantifierTransformationTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        builder = new PostgresCodeBuilder();
    }

    private String transform(String oracleSql) {
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + oracleSql);
        return builder.visit(parseResult.getTree()).trim().replaceAll("\\s+", " ");
    }

    @Test
    void testDistinct() {
        assertEquals("SELECT DISTINCT a , b FROM t", transform("SELECT DISTINCT a, b FROM t"));
    }

    /**
     * {@code UNIQUE} is Oracle's synonym for {@code DISTINCT}. Passing it through would not be a
     * loud failure — PostgreSQL parses {@code SELECT UNIQUE a} as a column named {@code unique}
     * aliased to {@code a}.
     */
    @Test
    void testUniqueBecomesDistinct() {
        assertEquals("SELECT DISTINCT a FROM t", transform("SELECT UNIQUE a FROM t"));
    }

    @Test
    void testAllIsPreserved() {
        assertEquals("SELECT ALL a FROM t", transform("SELECT ALL a FROM t"));
    }

    @Test
    void testNoQuantifier() {
        assertEquals("SELECT a FROM t", transform("SELECT a FROM t"));
    }

    @Test
    void testDistinctInSubquery() {
        assertEquals("SELECT a FROM t WHERE b IN ( SELECT DISTINCT c FROM u )",
            transform("SELECT a FROM t WHERE b IN (SELECT DISTINCT c FROM u)"));
    }

    @Test
    void testDistinctWithStar() {
        assertEquals("SELECT DISTINCT * FROM t", transform("SELECT DISTINCT * FROM t"));
    }

    /**
     * A hierarchical query is rewritten into a recursive CTE. The quantifier belongs on the final
     * SELECT — the base and recursive cases carry bookkeeping columns and must keep every traversal
     * row, so deduplicating there would prune rows the traversal still needs.
     */
    @Test
    void testDistinctWithConnectByAppliesToFinalSelect() {
        String result = transform(
            "SELECT DISTINCT emp_id FROM emp START WITH mgr_id IS NULL CONNECT BY PRIOR emp_id = mgr_id");

        assertTrue(result.contains("SELECT DISTINCT emp_id FROM emp_hierarchy"),
            "DISTINCT should sit on the SELECT that reads the CTE: " + result);
        assertFalse(result.contains("UNION ALL SELECT DISTINCT"),
            "The recursive case must not be deduplicated: " + result);
    }
}
