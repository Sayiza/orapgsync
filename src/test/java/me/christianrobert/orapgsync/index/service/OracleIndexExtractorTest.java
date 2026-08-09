package me.christianrobert.orapgsync.index.service;

import me.christianrobert.orapgsync.core.job.model.index.IndexKeyPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle has no true descending index - {@code (col DESC)} is stored as a function-based index
 * over a hidden virtual column, and the real column name only appears in
 * {@code ALL_IND_EXPRESSIONS}. Getting this wrong produces {@code CREATE INDEX} statements that
 * reference {@code SYS_NC0000n$} columns which do not exist in PostgreSQL, so the fold-back is
 * pinned here.
 */
class OracleIndexExtractorTest {

    @Test
    @DisplayName("a quoted column name is a descending column, not an expression")
    void quotedColumnBecomesColumnKey() {
        IndexKeyPart part = OracleIndexExtractor.keyPartFromExpression("\"HIRE_DATE\"", true);

        assertTrue(part.column(), "should be recognised as a plain column");
        assertFalse(part.isExpression());
        assertEquals("hire_date", part.expression());
        assertTrue(part.descending());
    }

    @Test
    void unquotedColumnNameBecomesColumnKey() {
        IndexKeyPart part = OracleIndexExtractor.keyPartFromExpression("HIRE_DATE", false);

        assertTrue(part.column());
        assertEquals("hire_date", part.expression());
    }

    @Test
    @DisplayName("a real function call stays an expression")
    void functionCallBecomesExpressionKey() {
        IndexKeyPart part = OracleIndexExtractor.keyPartFromExpression("UPPER(\"NAME\")", false);

        assertFalse(part.column());
        assertTrue(part.isExpression());
        assertEquals("UPPER(\"NAME\")", part.expression(), "expression text is preserved for transformation");
    }

    @Test
    void concatenationBecomesExpressionKey() {
        IndexKeyPart part = OracleIndexExtractor.keyPartFromExpression("\"FIRST\"||\"LAST\"", false);

        assertFalse(part.column());
        assertEquals("\"FIRST\"||\"LAST\"", part.expression());
    }

    @Test
    @DisplayName("identifiers containing $ and # are still columns")
    void oracleSpecialCharacterIdentifiersAreColumns() {
        assertTrue(OracleIndexExtractor.keyPartFromExpression("\"ORDER#\"", false).column());
        assertTrue(OracleIndexExtractor.keyPartFromExpression("\"COL$NAME\"", false).column());
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        IndexKeyPart part = OracleIndexExtractor.keyPartFromExpression("  \"DEPT_ID\"  ", false);

        assertTrue(part.column());
        assertEquals("dept_id", part.expression());
    }
}
