package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle string aggregation (LISTAGG, WM_CONCAT) → PostgreSQL STRING_AGG.
 *
 * <p>Behavioural equivalence against a real server is covered by
 * {@code PostgresListaggValidationTest}; these tests pin the emitted SQL and the rejections.
 */
class ListaggTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ========== AGGREGATE FORM ==========

    @Test
    void listaggWithDelimiterAndOrder() {
        assertTransforms(
                "SELECT dept_id, LISTAGG(ename, ',') WITHIN GROUP (ORDER BY ename) FROM emp GROUP BY dept_id",
                "SELECT dept_id , STRING_AGG( ( ename )::text , ',' ORDER BY ename ) "
                        + "FROM hr.emp GROUP BY dept_id");
    }

    @Test
    void listaggWithoutDelimiterUsesEmptyString() {
        // Oracle defaults the delimiter to NULL (no separator); PostgreSQL requires the argument
        assertTransforms(
                "SELECT LISTAGG(ename) WITHIN GROUP (ORDER BY ename) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , '' ORDER BY ename ) FROM hr.emp");
    }

    @Test
    void listaggWithoutWithinGroup() {
        assertTransforms(
                "SELECT LISTAGG(ename, ',') FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ) FROM hr.emp");
    }

    @Test
    void listaggDescOrderGetsNullsFirst() {
        // Oracle's DESC defaults to NULLS FIRST, PostgreSQL's to NULLS LAST - VisitOrderByClause
        // makes it explicit, and the aggregate ORDER BY has to inherit that
        assertTransforms(
                "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY sal DESC) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ORDER BY sal DESC NULLS FIRST ) FROM hr.emp");
    }

    @Test
    void listaggWithMultipleOrderKeys() {
        assertTransforms(
                "SELECT LISTAGG(ename, '; ') WITHIN GROUP (ORDER BY sal DESC, ename ASC) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , '; ' "
                        + "ORDER BY sal DESC NULLS FIRST , ename ASC ) FROM hr.emp");
    }

    @Test
    void listaggCastsNonTextMeasure() {
        // PostgreSQL resolves overloads by exact type: string_agg(numeric, unknown) does not exist
        assertTransforms(
                "SELECT LISTAGG(empno, ',') WITHIN GROUP (ORDER BY empno) FROM emp",
                "SELECT STRING_AGG( ( empno )::text , ',' ORDER BY empno ) FROM hr.emp");
    }

    @Test
    void listaggWithConcatenatedDelimiter() {
        assertTransforms(
                "SELECT LISTAGG(ename, ',' || ' ') WITHIN GROUP (ORDER BY ename) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' || ' ' ORDER BY ename ) FROM hr.emp");
    }

    // ========== ANALYTIC FORM ==========

    @Test
    void listaggAnalyticPinsTheFrameToTheWholePartition() {
        // PostgreSQL rejects aggregate ORDER BY inside OVER, so the ordering moves into the
        // window - and without an explicit full frame the default RANGE frame would turn this
        // into a running concatenation instead of the whole partition.
        assertTransforms(
                "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY sal DESC) "
                        + "OVER (PARTITION BY dept_id) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ) "
                        + "OVER ( PARTITION BY dept_id ORDER BY sal DESC NULLS FIRST "
                        + "ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING ) FROM hr.emp");
    }

    @Test
    void listaggAnalyticWithoutPartition() {
        assertTransforms(
                "SELECT LISTAGG(ename, ',') WITHIN GROUP (ORDER BY ename) OVER () FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ) "
                        + "OVER ( ORDER BY ename "
                        + "ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING ) FROM hr.emp");
    }

    @Test
    void listaggAnalyticWithoutOrderingOmitsTheFrame() {
        // With no ORDER BY, PostgreSQL's default frame is already the whole partition
        assertTransforms(
                "SELECT LISTAGG(ename, ',') OVER (PARTITION BY dept_id) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ) "
                        + "OVER ( PARTITION BY dept_id ) FROM hr.emp");
    }

    // ========== DISTINCT ==========

    @Test
    void listaggDistinctOrderedByTheMeasure() {
        // No ::text cast: PostgreSQL compares the ORDER BY expression against the argument list,
        // and a cast argument would need an identically cast key, which reorders numerics
        assertTransforms(
                "SELECT LISTAGG(DISTINCT ename, ',') WITHIN GROUP (ORDER BY ename) FROM emp",
                "SELECT STRING_AGG( DISTINCT ename , ',' ORDER BY ename ) FROM hr.emp");
    }

    @Test
    void listaggUniqueIsTreatedAsDistinct() {
        assertTransforms(
                "SELECT LISTAGG(UNIQUE ename, ',') WITHIN GROUP (ORDER BY ename) FROM emp",
                "SELECT STRING_AGG( DISTINCT ename , ',' ORDER BY ename ) FROM hr.emp");
    }

    @Test
    void listaggAllIsTheDefault() {
        assertTransforms(
                "SELECT LISTAGG(ALL ename, ',') WITHIN GROUP (ORDER BY ename) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ORDER BY ename ) FROM hr.emp");
    }

    @Test
    void listaggDistinctWithForeignOrderKeyIsRejected() {
        assertRejects(
                "SELECT LISTAGG(DISTINCT ename, ',') WITHIN GROUP (ORDER BY sal) FROM emp",
                "ordered by the aggregated expression itself");
    }

    @Test
    void listaggDistinctWithOverClauseIsRejected() {
        assertRejects(
                "SELECT LISTAGG(DISTINCT ename, ',') WITHIN GROUP (ORDER BY ename) "
                        + "OVER (PARTITION BY dept_id) FROM emp",
                "PostgreSQL has no DISTINCT form of a window aggregate");
    }

    // ========== ON OVERFLOW ==========

    @Test
    void listaggOnOverflowErrorIsDropped() {
        // Oracle's default, and vacuous against PostgreSQL's unbounded text
        assertTransforms(
                "SELECT LISTAGG(ename, ',' ON OVERFLOW ERROR) WITHIN GROUP (ORDER BY ename) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ORDER BY ename ) FROM hr.emp");
    }

    @Test
    void listaggOnOverflowTruncateIsRejected() {
        assertRejects(
                "SELECT LISTAGG(ename, ',' ON OVERFLOW TRUNCATE) WITHIN GROUP (ORDER BY ename) FROM emp",
                "ON OVERFLOW TRUNCATE is not supported");
    }

    @Test
    void listaggOnOverflowTruncateWithIndicatorIsRejected() {
        assertRejects(
                "SELECT LISTAGG(ename, ',' ON OVERFLOW TRUNCATE '..' WITH COUNT) "
                        + "WITHIN GROUP (ORDER BY ename) FROM emp",
                "ON OVERFLOW TRUNCATE is not supported");
    }

    // ========== WM_CONCAT ==========

    @Test
    void wmConcatBecomesCommaSeparatedStringAgg() {
        assertTransforms(
                "SELECT dept_id, WM_CONCAT(ename) FROM emp GROUP BY dept_id",
                "SELECT dept_id , STRING_AGG( ( ename )::text , ',' ) FROM hr.emp GROUP BY dept_id");
    }

    @Test
    void wmConcatAnalytic() {
        assertTransforms(
                "SELECT WM_CONCAT(ename) OVER (PARTITION BY dept_id) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ) "
                        + "OVER ( PARTITION BY dept_id ) FROM hr.emp");
    }

    @Test
    void wmConcatAnalyticWithOrderPinsTheFrame() {
        assertTransforms(
                "SELECT WM_CONCAT(ename) OVER (PARTITION BY dept_id ORDER BY sal) FROM emp",
                "SELECT STRING_AGG( ( ename )::text , ',' ) "
                        + "OVER ( PARTITION BY dept_id ORDER BY sal "
                        + "ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING ) FROM hr.emp");
    }

    // ========== HELPERS ==========

    private void assertTransforms(String oracleSql, String expectedPostgres) {
        TransformationContext context =
                new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed for: " + oracleSql);

        String postgresSql = new PostgresCodeBuilder(context).visit(parseResult.getTree());
        assertEquals(expectedPostgres, postgresSql.trim().replaceAll("\\s+", " "));
    }

    private void assertRejects(String oracleSql, String expectedMessageFragment) {
        TransformationContext context =
                new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed for: " + oracleSql);

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        TransformationException thrown = assertThrows(TransformationException.class,
                () -> builder.visit(parseResult.getTree()),
                "Should be rejected: " + oracleSql);

        assertTrue(thrown.getMessage().contains(expectedMessageFragment),
                "Expected message to contain \"" + expectedMessageFragment + "\" but was: "
                        + thrown.getMessage());
    }
}
