package me.christianrobert.orapgsync.transformer.analysis;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for silent parse truncation detection.
 *
 * <p>The grammar entry rules are not anchored to EOF, so the parser can end a statement early and
 * leave the rest of the source unread <em>without reporting an error</em>. The transformation then
 * succeeds on a fragment. These tests pin the behaviour the compatibility report relies on to make
 * that visible.</p>
 */
class ParseCompletenessTest {

    private AntlrParser parser;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
    }

    @Test
    void reportsUnreadTailOfModelQuery() {
        // The parser reads MODEL as a table alias, ends the statement and drops the rest.
        String oracleSql = "SELECT country, year, sales FROM sales_view "
                + "MODEL PARTITION BY (country) DIMENSION BY (year) MEASURES (sales) "
                + "RULES (sales[2025] = sales[2024] * 1.1)";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(),
                "This is the dangerous case: the parser reports no error at all");

        String tail = ParseCompleteness.unconsumedTail(parseResult);

        assertNotNull(tail, "The unread MODEL clause must be reported");
        assertTrue(tail.startsWith("PARTITION BY"),
                "Tail should start where the parser stopped, was: " + tail);
        assertEquals("PARTITION", ParseCompleteness.firstUnconsumedToken(tail));
    }

    @Test
    void fullyParsedQueryHasNoTail() {
        String oracleSql = "SELECT e.empno, e.ename FROM emp e WHERE e.deptno = 10 ORDER BY e.ename";

        assertNull(unconsumedTailOf(oracleSql), "A fully parsed query has no unread tail");
    }

    @Test
    void trailingSemicolonIsNotTruncation() {
        assertNull(unconsumedTailOf("SELECT empno FROM emp;"),
                "A statement terminator is not lost source");
    }

    @Test
    void trailingWhitespaceIsNotTruncation() {
        assertNull(unconsumedTailOf("SELECT empno FROM emp   \n\n  "));
    }

    @Test
    void trailingLineCommentIsNotTruncation() {
        assertNull(unconsumedTailOf("SELECT empno FROM emp -- the employee numbers"));
    }

    @Test
    void trailingBlockCommentIsNotTruncation() {
        assertNull(unconsumedTailOf("SELECT empno FROM emp /* the employee numbers */"));
    }

    @Test
    void snippetIsShortenedAndSingleLine() {
        String tail = "PARTITION BY (country)\n   DIMENSION BY (year)";

        assertEquals("PARTITION BY (country) DIMENSION BY (year)", ParseCompleteness.snippet(tail));
    }

    @Test
    void nullParseResultIsHandled() {
        assertNull(ParseCompleteness.unconsumedTail(null));
        assertNull(ParseCompleteness.firstUnconsumedToken(null));
        assertNull(ParseCompleteness.snippet(null));
    }

    private String unconsumedTailOf(String oracleSql) {
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(),
                "Parse should succeed for this test: " + parseResult.getErrorMessage());
        return ParseCompleteness.unconsumedTail(parseResult);
    }
}
