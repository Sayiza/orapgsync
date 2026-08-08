package me.christianrobert.orapgsync.transformer.analysis;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pre-flight construct detector.
 *
 * <p>The detector answers "which catalogued Oracle constructs are in this source" independently
 * of whether the transformation succeeds — that is what makes silently dropped constructs
 * visible in the compatibility report.</p>
 */
class ConstructDetectorTest {

    private AntlrParser parser;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
    }

    // ========== SQL CONSTRUCTS ==========

    @Test
    void detectsPivotClause() {
        String oracleSql = """
                SELECT * FROM (SELECT deptno, job, sal FROM emp)
                PIVOT (SUM(sal) FOR job IN ('CLERK' AS clerk, 'MANAGER' AS mgr))
                """;

        List<DetectedConstruct> detected = detectInSql(oracleSql);

        DetectedConstruct pivot = findById(detected, "PIVOT");
        assertNotNull(pivot, "PIVOT should be detected");
        assertEquals("PIVOT", pivot.displayName());
        assertTrue(pivot.snippet().toUpperCase().startsWith("PIVOT"),
                "Snippet should show the construct source, was: " + pivot.snippet());
    }

    @Test
    void detectsUnpivotClause() {
        String oracleSql = """
                SELECT * FROM sales_data
                UNPIVOT (amount FOR quarter IN (q1, q2, q3, q4))
                """;

        assertNotNull(findById(detectInSql(oracleSql), "UNPIVOT"), "UNPIVOT should be detected");
    }

    @Test
    void detectsCursorExpression() {
        String oracleSql = "SELECT d.dname, CURSOR(SELECT e.ename FROM emp e WHERE e.deptno = d.deptno) FROM dept d";

        assertNotNull(findById(detectInSql(oracleSql), "CURSOR_EXPRESSION"),
                "CURSOR() expression should be detected");
    }

    @Test
    void detectsOrderSiblingsBy() {
        String oracleSql = """
                SELECT empno, ename FROM emp
                START WITH mgr IS NULL
                CONNECT BY PRIOR empno = mgr
                ORDER SIBLINGS BY ename
                """;

        DetectedConstruct siblings = findById(detectInSql(oracleSql), "ORDER_SIBLINGS_BY");
        assertNotNull(siblings, "ORDER SIBLINGS BY should be detected");
        assertEquals(ConstructSupport.IGNORED, siblings.support(),
                "ORDER SIBLINGS BY is dropped by the transformer, not handled");
    }

    @Test
    void plainOrderByIsNotReportedAsSiblings() {
        String oracleSql = "SELECT empno FROM emp ORDER BY ename";

        assertNull(findById(detectInSql(oracleSql), "ORDER_SIBLINGS_BY"));
    }

    @Test
    void reportsNothingForAPlainQuery() {
        String oracleSql = "SELECT e.empno, e.ename FROM emp e WHERE e.deptno = 10 ORDER BY e.ename";

        assertTrue(detectInSql(oracleSql).isEmpty(),
                "A plain query must not produce findings, was: " + detectInSql(oracleSql));
    }

    @Test
    void reportsLineNumberOfConstruct() {
        String oracleSql = """
                SELECT *
                FROM (SELECT deptno, job, sal FROM emp)
                PIVOT (SUM(sal) FOR job IN ('CLERK' AS clerk))
                """;

        DetectedConstruct pivot = findById(detectInSql(oracleSql), "PIVOT");
        assertNotNull(pivot);
        assertEquals(3, pivot.line(), "PIVOT starts on the third line");
    }

    @Test
    void detectsMultipleOccurrencesSeparately() {
        String oracleSql = """
                SELECT * FROM (
                    SELECT d.dname,
                           CURSOR(SELECT e.ename FROM emp e WHERE e.deptno = d.deptno) AS emps,
                           CURSOR(SELECT p.pname FROM proj p WHERE p.deptno = d.deptno) AS projs
                    FROM dept d
                )
                """;

        long cursorCount = detectInSql(oracleSql).stream()
                .filter(c -> "CURSOR_EXPRESSION".equals(c.id()))
                .count();

        assertEquals(2, cursorCount, "Both CURSOR() expressions should be reported");
    }

    // ========== PL/SQL CONSTRUCTS ==========

    @Test
    void detectsAutonomousTransactionPragma() {
        String plsql = """
                FUNCTION log_it(p_msg VARCHAR2) RETURN NUMBER IS
                    PRAGMA AUTONOMOUS_TRANSACTION;
                BEGIN
                    INSERT INTO log_table (msg) VALUES (p_msg);
                    COMMIT;
                    RETURN 1;
                END;
                """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + parseResult.getErrorMessage());

        DetectedConstruct pragma = findById(ConstructDetector.detect(parseResult.getTree()),
                "AUTONOMOUS_TRANSACTION");

        assertNotNull(pragma, "PRAGMA AUTONOMOUS_TRANSACTION should be detected");
        assertEquals(ConstructSupport.IGNORED, pragma.support(),
                "The pragma is silently ignored by the transformer");
    }

    @Test
    void exceptionInitPragmaIsNotReported() {
        String plsql = """
                FUNCTION f RETURN NUMBER IS
                    my_error EXCEPTION;
                    PRAGMA EXCEPTION_INIT(my_error, -20001);
                BEGIN
                    RETURN 1;
                END;
                """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + parseResult.getErrorMessage());

        assertNull(findById(ConstructDetector.detect(parseResult.getTree()), "AUTONOMOUS_TRANSACTION"),
                "EXCEPTION_INIT is supported and must not be reported as an autonomous transaction");
    }

    @Test
    void detectsBulkCollectInto() {
        String plsql = """
                FUNCTION f RETURN NUMBER IS
                    TYPE name_tab IS TABLE OF VARCHAR2(100);
                    l_names name_tab;
                BEGIN
                    SELECT ename BULK COLLECT INTO l_names FROM emp;
                    RETURN l_names.COUNT;
                END;
                """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + parseResult.getErrorMessage());

        assertNotNull(findById(ConstructDetector.detect(parseResult.getTree()), "BULK_COLLECT"),
                "BULK COLLECT INTO should be detected");
    }

    @Test
    void plainSelectIntoIsNotReportedAsBulkCollect() {
        String plsql = """
                FUNCTION f RETURN NUMBER IS
                    l_name VARCHAR2(100);
                BEGIN
                    SELECT ename INTO l_name FROM emp WHERE empno = 1;
                    RETURN 1;
                END;
                """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + parseResult.getErrorMessage());

        assertNull(findById(ConstructDetector.detect(parseResult.getTree()), "BULK_COLLECT"),
                "A plain SELECT INTO is not a bulk collect");
    }

    // ========== ROBUSTNESS ==========

    @Test
    void nullTreeProducesNoDetections() {
        assertTrue(ConstructDetector.detect(null).isEmpty());
    }

    // ========== CATALOG ==========

    @Test
    void supportStatusIsDerivedFromTheTransformer() {
        // PIVOT has no visit method in PostgresCodeBuilder, so it must be reported as NO_VISITOR.
        // If someone implements visitPivot_clause, this expectation flips automatically - the
        // catalog is not allowed to carry a hand-maintained status for it.
        ConstructRule pivot = ConstructCatalog.rules().stream()
                .filter(r -> "PIVOT".equals(r.id()))
                .findFirst()
                .orElseThrow();

        assertNull(pivot.supportOverride(), "PIVOT must derive its status, not hard-code it");
        assertEquals(ConstructSupport.NO_VISITOR, ConstructCatalog.supportOf(pivot));
    }

    @Test
    void catalogIdsAreUnique() {
        long distinctIds = ConstructCatalog.rules().stream().map(ConstructRule::id).distinct().count();
        assertEquals(ConstructCatalog.rules().size(), distinctIds, "Construct ids must be unique");
    }

    // ========== HELPERS ==========

    private List<DetectedConstruct> detectInSql(String oracleSql) {
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(),
                "Parse should succeed for this test: " + parseResult.getErrorMessage());
        return ConstructDetector.detect(parseResult.getTree());
    }

    private DetectedConstruct findById(List<DetectedConstruct> detected, String id) {
        return detected.stream().filter(c -> id.equals(c.id())).findFirst().orElse(null);
    }
}
