package me.christianrobert.orapgsync.trigger.transformer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Injects RETURN statements into PostgreSQL trigger function bodies.
 *
 * <p>PostgreSQL trigger functions MUST return a value, unlike Oracle triggers
 * which have no return statement. The return value depends on the trigger
 * timing and level:</p>
 *
 * <table border="1">
 *   <tr>
 *     <th>Trigger Timing</th>
 *     <th>Trigger Level</th>
 *     <th>Return Value</th>
 *     <th>Effect</th>
 *   </tr>
 *   <tr>
 *     <td>BEFORE</td>
 *     <td>ROW</td>
 *     <td>NEW</td>
 *     <td>Modified row will be processed</td>
 *   </tr>
 *   <tr>
 *     <td>BEFORE</td>
 *     <td>ROW</td>
 *     <td>NULL</td>
 *     <td>Skip operation for this row</td>
 *   </tr>
 *   <tr>
 *     <td>AFTER</td>
 *     <td>ROW</td>
 *     <td>NULL (or any)</td>
 *     <td>Ignored by PostgreSQL</td>
 *   </tr>
 *   <tr>
 *     <td>BEFORE/AFTER</td>
 *     <td>STATEMENT</td>
 *     <td>NULL</td>
 *     <td>Ignored by PostgreSQL</td>
 *   </tr>
 *   <tr>
 *     <td>INSTEAD OF</td>
 *     <td>ROW</td>
 *     <td>NULL (or any)</td>
 *     <td>Ignored by PostgreSQL</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * String plpgsqlBody = "BEGIN\n  INSERT INTO audit_log VALUES (NEW.id);\nEND;";
 * String withReturn = TriggerReturnInjector.injectReturn(plpgsqlBody, "BEFORE", "ROW");
 * // Result: "BEGIN\n  INSERT INTO audit_log VALUES (NEW.id);\n  RETURN NEW;\nEND;"
 * </pre>
 */
public class TriggerReturnInjector {

    private static final Logger log = LoggerFactory.getLogger(TriggerReturnInjector.class);

    /**
     * Injects a RETURN statement before the final END if not already present.
     *
     * @param plpgsqlBody Transformed PL/pgSQL trigger body (after colon removal)
     * @param triggerType Trigger timing: BEFORE, AFTER, or INSTEAD OF
     * @param triggerLevel Trigger level: ROW or STATEMENT
     * @return Trigger body with RETURN statement
     * @throws IllegalArgumentException if trigger body doesn't have an END statement
     */
    public static String injectReturn(String plpgsqlBody, String triggerType, String triggerLevel) {
        if (plpgsqlBody == null || plpgsqlBody.trim().isEmpty()) {
            throw new IllegalArgumentException("Trigger body cannot be null or empty");
        }

        // Check if trigger body already has a RETURN statement
        if (hasReturnStatement(plpgsqlBody)) {
            log.debug("Trigger body already has RETURN statement, skipping injection");
            return plpgsqlBody;
        }

        // Determine what value to return
        String returnValue = determineReturnValue(triggerType, triggerLevel);

        // Find the last END and insert RETURN before it
        return insertBeforeLastEnd(plpgsqlBody, returnValue);
    }

    /**
     * Checks if the trigger body already contains a RETURN statement.
     *
     * <p>This is a simple check that looks for the keyword "RETURN" followed
     * by a semicolon or whitespace (case-insensitive).</p>
     *
     * @param body Trigger body to check
     * @return true if RETURN statement found, false otherwise
     */
    private static boolean hasReturnStatement(String body) {
        // Simple pattern: RETURN followed by space, semicolon, or end of string
        // Use (?s) flag to make . match newlines (DOTALL mode)
        String upperBody = body.toUpperCase();
        return upperBody.matches("(?s).*\\bRETURN\\s+.*") ||
               upperBody.matches("(?s).*\\bRETURN;.*");
    }

    /**
     * Determines the appropriate return value based on trigger characteristics.
     *
     * <p>Logic:
     * <ul>
     *   <li>BEFORE ROW triggers → RETURN NEW (allows row modification)</li>
     *   <li>All other triggers → RETURN NULL (value ignored by PostgreSQL)</li>
     * </ul>
     *
     * @param triggerType BEFORE, AFTER, or INSTEAD OF
     * @param triggerLevel ROW or STATEMENT
     * @return Return value: "NEW" or "NULL"
     */
    private static String determineReturnValue(String triggerType, String triggerLevel) {
        if (triggerType == null || triggerLevel == null) {
            log.warn("Trigger type or level is null, defaulting to RETURN NULL");
            return "NULL";
        }

        // BEFORE ROW triggers should return NEW (most common case)
        if ("BEFORE".equalsIgnoreCase(triggerType) && "ROW".equalsIgnoreCase(triggerLevel)) {
            return "NEW";
        }

        // All other cases: return NULL
        // - AFTER ROW: return value ignored
        // - STATEMENT level: return value ignored
        // - INSTEAD OF: return value ignored
        return "NULL";
    }

    /**
     * Inserts RETURN statement(s) to ensure all execution paths have a RETURN.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>If there's an EXCEPTION block, insert RETURN before EXCEPTION (normal path)
     *       AND before the final END (exception path)</li>
     *   <li>If there's no EXCEPTION block, insert RETURN before the final END</li>
     * </ol>
     *
     * <p>This ensures that both normal execution and exception handling paths
     * have a RETURN statement, which PostgreSQL requires.</p>
     *
     * @param body Trigger body
     * @param returnValue Value to return ("NEW" or "NULL")
     * @return Body with RETURN statement(s) inserted
     * @throws IllegalArgumentException if no END statement found
     */
    private static String insertBeforeLastEnd(String body, String returnValue) {
        String upperBody = body.toUpperCase();

        // Find the last occurrence of END followed by semicolon or whitespace
        int lastEndIndex = findLastEnd(upperBody);

        if (lastEndIndex == -1) {
            throw new IllegalArgumentException("No END statement found in trigger body");
        }

        // Check if there's an EXCEPTION block
        int exceptionIndex = findExceptionKeyword(upperBody);

        if (exceptionIndex != -1 && exceptionIndex < lastEndIndex) {
            // Has EXCEPTION block - need RETURN on both paths
            return insertReturnWithException(body, returnValue, exceptionIndex, lastEndIndex);
        } else {
            // No EXCEPTION block - single RETURN before END
            return insertSingleReturn(body, returnValue, lastEndIndex);
        }
    }

    /**
     * Inserts a single RETURN statement before the final END.
     * Used when there is no EXCEPTION block.
     */
    private static String insertSingleReturn(String body, String returnValue, int lastEndIndex) {
        String indentation = detectIndentation(body, lastEndIndex);
        String returnStatement = indentation + "RETURN " + returnValue + ";\n";

        String beforeEnd = body.substring(0, lastEndIndex);
        String fromEnd = body.substring(lastEndIndex);

        return beforeEnd + returnStatement + fromEnd;
    }

    /**
     * Inserts RETURN statements for both normal and exception paths.
     * Used when there is an EXCEPTION block.
     *
     * <p>Structure:</p>
     * <pre>
     * BEGIN
     *   statements...
     *   RETURN value;  -- inserted for normal path
     * EXCEPTION
     *   WHEN OTHERS THEN
     *     handler statements...
     *   RETURN value;  -- inserted for exception path
     * END;
     * </pre>
     */
    private static String insertReturnWithException(String body, String returnValue,
                                                     int exceptionIndex, int lastEndIndex) {
        // Insert RETURN before EXCEPTION (normal path)
        String indentationBeforeException = detectIndentation(body, exceptionIndex);
        String returnBeforeException = indentationBeforeException + "RETURN " + returnValue + ";\n";

        // Insert RETURN before END (exception path)
        // Note: lastEndIndex will shift after first insertion, so we calculate the offset
        String indentationBeforeEnd = detectIndentation(body, lastEndIndex);
        String returnBeforeEnd = indentationBeforeEnd + "RETURN " + returnValue + ";\n";

        // Build the result: before EXCEPTION + RETURN + from EXCEPTION to before END + RETURN + from END
        StringBuilder result = new StringBuilder();
        result.append(body.substring(0, exceptionIndex));
        result.append(returnBeforeException);
        result.append(body.substring(exceptionIndex, lastEndIndex));
        result.append(returnBeforeEnd);
        result.append(body.substring(lastEndIndex));

        return result.toString();
    }

    /**
     * Finds the EXCEPTION keyword in the trigger body.
     *
     * <p>Looks for standalone EXCEPTION keyword (not part of another word).</p>
     *
     * @param upperBody Trigger body in uppercase
     * @return Index of EXCEPTION keyword, or -1 if not found
     */
    private static int findExceptionKeyword(String upperBody) {
        int index = upperBody.indexOf("EXCEPTION");
        while (index != -1) {
            // Check word boundaries
            boolean validStart = index == 0 || !Character.isLetterOrDigit(upperBody.charAt(index - 1));
            boolean validEnd = index + 9 >= upperBody.length() ||
                               !Character.isLetterOrDigit(upperBody.charAt(index + 9));

            if (validStart && validEnd) {
                return index;
            }

            // Try to find next occurrence
            index = upperBody.indexOf("EXCEPTION", index + 1);
        }
        return -1;
    }

    /**
     * Finds the index of the last END keyword in the trigger body.
     *
     * <p>Looks for END followed by:
     * <ul>
     *   <li>Semicolon (END;)</li>
     *   <li>Whitespace (END )</li>
     *   <li>End of string</li>
     * </ul>
     *
     * @param upperBody Trigger body in uppercase
     * @return Index of last END keyword, or -1 if not found
     */
    private static int findLastEnd(String upperBody) {
        // Try to find "END;" first (most common)
        int endSemicolon = upperBody.lastIndexOf("END;");
        if (endSemicolon != -1) {
            // Make sure it's a word boundary (not part of another word like APPEND;)
            if (endSemicolon == 0 || !Character.isLetterOrDigit(upperBody.charAt(endSemicolon - 1))) {
                return endSemicolon;
            }
        }

        // Try to find "END " (with space)
        int endSpace = upperBody.lastIndexOf("END ");
        if (endSpace != -1) {
            // Make sure it's a word boundary
            if (endSpace == 0 || !Character.isLetterOrDigit(upperBody.charAt(endSpace - 1))) {
                return endSpace;
            }
        }

        // Try to find END at the end of the string
        if (upperBody.endsWith("END")) {
            int endIndex = upperBody.length() - 3;
            if (endIndex == 0 || !Character.isLetterOrDigit(upperBody.charAt(endIndex - 1))) {
                return endIndex;
            }
        }

        return -1;
    }

    /**
     * Detects the indentation level of the END statement.
     *
     * <p>Looks backwards from the END keyword to find the start of the line,
     * then counts leading whitespace characters.</p>
     *
     * @param body Original trigger body
     * @param endIndex Index of END keyword
     * @return Indentation string (spaces or tabs)
     */
    private static String detectIndentation(String body, int endIndex) {
        // Find the start of the line containing END
        int lineStart = endIndex;
        while (lineStart > 0 && body.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        // Extract indentation (whitespace before END)
        StringBuilder indentation = new StringBuilder();
        for (int i = lineStart; i < endIndex; i++) {
            char c = body.charAt(i);
            if (c == ' ' || c == '\t') {
                indentation.append(c);
            } else {
                break;
            }
        }

        return indentation.toString();
    }
}
