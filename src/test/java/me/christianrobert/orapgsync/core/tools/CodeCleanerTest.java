package me.christianrobert.orapgsync.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive tests for CodeCleaner.removeComments()
 *
 * Purpose: Verify comment removal before package segmentation scanning.
 * This is critical for simplifying the FunctionBoundaryScanner state machine.
 */
class CodeCleanerTest {

    // ========== Basic Comment Removal ==========

    @Test
    void removeComments_singleLineComment() {
        String input = "SELECT * FROM emp; -- This is a comment\nSELECT * FROM dept;";
        String expected = "SELECT * FROM emp; \nSELECT * FROM dept;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Single-line comment should be removed");
    }

    @Test
    void removeComments_multiLineComment() {
        String input = "SELECT /* comment */ * FROM emp;";
        String expected = "SELECT  * FROM emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Multi-line comment should be removed");
    }

    @Test
    void removeComments_multiLineCommentSpanningLines() {
        String input = "SELECT /*\n  This comment\n  spans multiple\n  lines\n*/ * FROM emp;";
        String expected = "SELECT  * FROM emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Multi-line comment spanning lines should be removed");
    }

    @Test
    void removeComments_commentAtEndOfLine() {
        String input = "SELECT col1, col2 -- end of line comment\nFROM emp;";
        String expected = "SELECT col1, col2 \nFROM emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Comment at end of line should be removed, newline preserved");
    }

    @Test
    void removeComments_multipleCommentTypes() {
        String input = "SELECT * -- single line\nFROM /* multi-line */ emp;";
        String expected = "SELECT * \nFROM  emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Both comment types should be removed");
    }

    // ========== String Literal Preservation ==========

    @Test
    void removeComments_stringWithSingleLineCommentSyntax() {
        String input = "SELECT 'Value with -- fake comment' FROM dual;";
        String expected = "SELECT 'Value with -- fake comment' FROM dual;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Comment syntax inside string should be preserved");
    }

    @Test
    void removeComments_stringWithMultiLineCommentSyntax() {
        String input = "SELECT 'Value with /* fake */ comment' FROM dual;";
        String expected = "SELECT 'Value with /* fake */ comment' FROM dual;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Comment syntax inside string should be preserved");
    }

    @Test
    void removeComments_stringWithEscapedQuote() {
        String input = "SELECT 'O''Reilly' FROM dual; -- comment";
        // Note: No newline at end of input, so no newline in output
        String expected = "SELECT 'O''Reilly' FROM dual; ";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Escaped quote should not end string, comment should be removed");
    }

    @Test
    void removeComments_plsqlStringWithCommentSyntax() {
        String input = "v_sql := 'SELECT * FROM emp -- this should stay';";
        String expected = "v_sql := 'SELECT * FROM emp -- this should stay';";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Comment syntax inside PL/SQL string should be preserved");
    }

    @Test
    void removeComments_multipleStringsAndComments() {
        String input = "SELECT 'str1 -- fake' -- real comment\n, 'str2 /* fake */' FROM dual; /* real */";
        String expected = "SELECT 'str1 -- fake' \n, 'str2 /* fake */' FROM dual; ";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Should distinguish between real comments and string contents");
    }

    // ========== Nested Comments (Oracle Doesn't Support) ==========

    @Test
    void removeComments_nestedCommentAttempt() {
        // Oracle doesn't support nested /* /* */ */ comments
        // Expected behavior: First */ closes the comment, leaving "end */" as code
        String input = "SELECT /* outer /* inner */ end */ col FROM emp;";
        String expected = "SELECT  end */ col FROM emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Nested comment attempt should close at first */");
    }

    @Test
    void removeComments_consecutiveMultiLineComments() {
        String input = "SELECT /* comment1 */ /* comment2 */ col FROM emp;";
        String expected = "SELECT   col FROM emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Consecutive multi-line comments should both be removed");
    }

    // ========== Edge Cases ==========

    @Test
    void removeComments_emptyString() {
        String input = "";
        String expected = "";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Empty string should remain empty");
    }

    @Test
    void removeComments_onlyComments() {
        String input = "-- comment1\n/* comment2 */\n-- comment3";
        String expected = "\n\n";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Code with only comments should leave only newlines");
    }

    @Test
    void removeComments_noComments() {
        String input = "SELECT * FROM emp WHERE id = 1;";
        String expected = "SELECT * FROM emp WHERE id = 1;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Code without comments should remain unchanged");
    }

    @Test
    void removeComments_commentWithinSQL() {
        String input = "SELECT col1, /* inline comment */ col2 FROM emp;";
        String expected = "SELECT col1,  col2 FROM emp;";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Inline comment should be removed");
    }

    // ========== Real-World PL/SQL Code ==========

    @Test
    void removeComments_plsqlFunctionWithComments() {
        String input = """
            FUNCTION calculate_bonus(emp_id NUMBER) RETURN NUMBER IS
              v_base NUMBER; -- base salary
              /* Bonus calculation variables */
              v_bonus NUMBER;
            BEGIN
              -- Get base salary
              SELECT base_sal INTO v_base FROM employees WHERE id = emp_id;
              /* Calculate bonus
                 Based on department rules */
              v_bonus := v_base * 0.1;
              RETURN v_bonus; -- return result
            END;
            """;

        // Note: Spaces before/after comments are preserved (this is correct behavior)
        String expected = """
            FUNCTION calculate_bonus(emp_id NUMBER) RETURN NUMBER IS
              v_base NUMBER;

              v_bonus NUMBER;
            BEGIN

              SELECT base_sal INTO v_base FROM employees WHERE id = emp_id;

              v_bonus := v_base * 0.1;
              RETURN v_bonus;
            END;
            """;

        String actual = CodeCleaner.removeComments(input);

        // Verify comments are removed by checking key content (ignore trailing spaces)
        assertTrue(actual.contains("v_base NUMBER;"), "Should contain v_base declaration");
        assertTrue(actual.contains("v_bonus NUMBER;"), "Should contain v_bonus declaration");
        assertFalse(actual.contains("-- base salary"), "Should not contain single-line comment");
        assertFalse(actual.contains("/* Bonus calculation"), "Should not contain multi-line comment");
        assertFalse(actual.contains("-- Get base salary"), "Should not contain comment");
        assertFalse(actual.contains("-- return result"), "Should not contain end comment");
    }

    @Test
    void removeComments_packageBodyWithComments() {
        String input = """
            CREATE OR REPLACE PACKAGE BODY test_pkg AS
              -- Package variables
              g_counter INTEGER := 0; /* Initial value */

              /* Private function */
              FUNCTION increment RETURN NUMBER IS
              BEGIN
                g_counter := g_counter + 1; -- Increment
                RETURN g_counter;
              END; -- increment
            END test_pkg;
            """;

        String actual = CodeCleaner.removeComments(input);

        // Verify comments are removed by checking key content (ignore trailing spaces)
        assertTrue(actual.contains("g_counter INTEGER := 0;"), "Should contain variable declaration");
        assertTrue(actual.contains("FUNCTION increment RETURN NUMBER"), "Should contain function declaration");
        assertTrue(actual.contains("g_counter := g_counter + 1;"), "Should contain increment statement");
        assertFalse(actual.contains("-- Package variables"), "Should not contain single-line comment");
        assertFalse(actual.contains("/* Initial value */"), "Should not contain inline comment");
        assertFalse(actual.contains("/* Private function */"), "Should not contain multi-line comment");
        assertFalse(actual.contains("-- Increment"), "Should not contain end-of-line comment");
        assertFalse(actual.contains("-- increment"), "Should not contain END comment");
    }

    // ========== Special Characters in Strings ==========

    @Test
    void removeComments_stringWithSlashAndStar() {
        String input = "v_path := '/home/user/*/files';";
        String expected = "v_path := '/home/user/*/files';";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Slash and star in string should be preserved");
    }

    @Test
    void removeComments_stringWithDoubleDash() {
        String input = "v_msg := 'Error -- something went wrong';";
        String expected = "v_msg := 'Error -- something went wrong';";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "Double dash in string should be preserved");
    }

    @Test
    void removeComments_urlInString() {
        String input = "v_url := 'http://example.com/path'; -- comment";
        // Note: No newline at end of input, so no newline in output
        String expected = "v_url := 'http://example.com/path'; ";
        String actual = CodeCleaner.removeComments(input);
        assertEquals(expected, actual, "URL with slashes should be preserved, comment removed");
    }
}
