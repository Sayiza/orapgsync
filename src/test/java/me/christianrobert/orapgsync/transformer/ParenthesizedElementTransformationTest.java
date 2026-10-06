package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for redundant parentheses around an element reference.
 *
 * <p>Grammar: {@code general_element : ... | '(' general_element ')'}. That alternative is tried
 * before {@code atom}'s {@code '(' expressions_ ')'}, so a parenthesized <em>name</em> — as opposed
 * to a parenthesized <em>expression</em> such as {@code (a + 1)} — never reaches the expression
 * path. Every form below used to abort the whole view with
 * "Parenthesized general_element not yet supported".
 */
class ParenthesizedElementTransformationTest {

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
    void testParenthesizedColumn() {
        assertEquals("SELECT ( a ) FROM t", transform("SELECT (a) FROM t"));
    }

    @Test
    void testParenthesizedQualifiedColumn() {
        assertEquals("SELECT ( t . a ) FROM t", transform("SELECT (t.a) FROM t"));
    }

    @Test
    void testParenthesizedFunctionCall() {
        assertEquals("SELECT ( max( a ) ) FROM t", transform("SELECT (max(a)) FROM t"));
    }

    @Test
    void testNestedParentheses() {
        assertEquals("SELECT ( ( a ) ) FROM t", transform("SELECT ((a)) FROM t"));
    }

    @Test
    void testParenthesizedElementInWhereClause() {
        assertEquals("SELECT * FROM t WHERE ( a ) = 1", transform("SELECT * FROM t WHERE (a) = 1"));
    }

    /**
     * The parentheses must survive into the output. Emitting {@code a . b} instead would turn
     * composite field access into a table-qualified column reference — the same text, a different
     * relation, and no error from PostgreSQL if a table happens to be named {@code a}.
     */
    @Test
    void testParenthesizedHeadOfDotChainKeepsParentheses() {
        assertEquals("SELECT ( a ) . b FROM t", transform("SELECT (a).b FROM t"));
    }

    /**
     * A pseudo-column inside parentheses still has to be recognised — the transformation has to
     * happen on the inner element, not be skipped because the outer node holds no name parts.
     */
    @Test
    void testParenthesizedPseudoColumn() {
        assertEquals("SELECT ( CURRENT_TIMESTAMP )", transform("SELECT (sysdate) FROM dual"));
    }

    /**
     * A parenthesized expression is a different grammar path and must keep working unchanged.
     */
    @Test
    void testParenthesizedExpressionIsUnaffected() {
        assertEquals("SELECT (a + 1) FROM t", transform("SELECT (a + 1) FROM t"));
    }

    /**
     * The reported view: a scalar subquery whose aggregate carries redundant parentheses.
     */
    @Test
    void testRedundantParenthesesAroundAggregateInScalarSubquery() {
        String oracleSql =
            "SELECT e.enrolment_nr, e.last_edit changed_on "
                + "FROM exam_results e, programmes_v s "
                + "WHERE e.last_edit = ( SELECT ( max( last_edit ) ) "
                + "FROM exam_results er WHERE er.enrolment_nr = s.enrolment_nr )";

        assertEquals(
            "SELECT e . enrolment_nr , e . last_edit AS changed_on "
                + "FROM exam_results e, programmes_v s "
                + "WHERE e . last_edit = (SELECT ( max( last_edit ) ) "
                + "FROM exam_results er WHERE er . enrolment_nr = s . enrolment_nr)",
            transform(oracleSql));
    }
}
