package me.christianrobert.orapgsync.transformer.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes all function/procedure implementations from package bodies.
 *
 * Used to create "reduced packages" for efficient variable extraction. Instead of parsing
 * a 5000-line package body to extract 20 variable declarations, we remove all functions
 * and parse only the remaining 20 lines.
 *
 * **Performance Benefit:**
 * - Full body: 5000 lines → 2GB AST, 3 minutes
 * - Reduced body: 20 lines → 100KB AST, <10ms
 *
 * **Example Transformation:**
 * ```sql
 * -- Input (Full package body - 5000 lines)
 * CREATE OR REPLACE PACKAGE BODY hr.emp_pkg AS
 *   g_counter INTEGER := 0;
 *   g_status VARCHAR2(20) := 'ACTIVE';
 *
 *   TYPE salary_info_t IS RECORD (
 *     base NUMBER,
 *     bonus NUMBER
 *   );
 *
 *   FUNCTION get_salary(...) IS ... END; -- 500 lines
 *   FUNCTION calculate_bonus(...) IS ... END; -- 800 lines
 *   -- 48 more functions: 3700 lines
 * END emp_pkg;
 *
 * -- Output (Reduced package body - 20 lines)
 * CREATE OR REPLACE PACKAGE BODY hr.emp_pkg AS
 *   g_counter INTEGER := 0;
 *   g_status VARCHAR2(20) := 'ACTIVE';
 *
 *   TYPE salary_info_t IS RECORD (
 *     base NUMBER,
 *     bonus NUMBER
 *   );
 *
 *   -- All functions removed
 * END emp_pkg;
 * ```
 *
 * The reduced body contains:
 * - Package variables (g_counter, g_status)
 * - Type declarations (salary_info_t)
 * - Constants, exceptions
 * - Package header/footer
 *
 * All function/procedure implementations are removed.
 */
public class PackageBodyReducer {

    private static final Logger log = LoggerFactory.getLogger(PackageBodyReducer.class);

    /**
     * Removes all function/procedure implementations from package body.
     *
     * The resulting reduced body contains only:
     * - Package variables
     * - Type declarations
     * - Constants and exceptions
     * - Package structure (CREATE/END)
     *
     * @param packageBodySql Full package body SQL (comments already removed)
     * @param segments Scanned function segments identifying what to remove
     * @return Reduced package body with all functions removed
     */
    public String removeAllFunctions(String packageBodySql, PackageSegments segments) {
        log.debug("Reducing package body ({} chars, {} functions)",
            packageBodySql.length(),
            segments.getFunctionCount());

        if (segments.getFunctionCount() == 0) {
            log.debug("No functions to remove, returning original");
            return packageBodySql;
        }

        StringBuilder reduced = new StringBuilder();
        int currentPos = 0;

        // Copy everything EXCEPT function bodies
        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            // Copy text before this function
            if (segment.getStartPos() > currentPos) {
                reduced.append(packageBodySql.substring(currentPos, segment.getStartPos()));
            }

            // Skip function entirely (don't copy)
            currentPos = segment.getEndPos();
        }

        // Copy remaining text after last function
        if (currentPos < packageBodySql.length()) {
            reduced.append(packageBodySql.substring(currentPos));
        }

        String result = reduced.toString();

        log.debug("Reduced package body from {} chars to {} chars ({} reduction)",
            packageBodySql.length(),
            result.length(),
            String.format("%.1f%%", 100.0 * (1.0 - (double) result.length() / packageBodySql.length())));

        return result;
    }

    /**
     * Estimates the size reduction that would result from removing functions.
     *
     * @param packageBodyLength Original package body length
     * @param segments Function segments
     * @return Estimated reduced size in characters
     */
    public int estimateReducedSize(int packageBodyLength, PackageSegments segments) {
        int removedChars = 0;
        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            removedChars += segment.getLength();
        }
        return packageBodyLength - removedChars;
    }

    /**
     * Calculates the percentage reduction in size.
     *
     * @param originalSize Original package body size
     * @param reducedSize Reduced package body size
     * @return Percentage reduction (0-100)
     */
    public double calculateReductionPercentage(int originalSize, int reducedSize) {
        if (originalSize == 0) {
            return 0.0;
        }
        return 100.0 * (1.0 - (double) reducedSize / originalSize);
    }
}
