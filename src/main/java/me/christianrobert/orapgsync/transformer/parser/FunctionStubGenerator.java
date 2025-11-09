package me.christianrobert.orapgsync.transformer.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates stub functions by replacing full implementations with minimal bodies.
 *
 * Stubs are used for fast metadata extraction during the extraction job. Instead of parsing
 * the full function (which can be 500+ lines), we parse a 4-line stub containing only the
 * signature and a simple RETURN statement.
 *
 * **Performance Benefit:**
 * - Full function: 800 lines → 40MB AST, 200ms parse
 * - Stub function: 4 lines → 200B AST, <1ms parse
 *
 * **Example Transformation:**
 * ```sql
 * -- Input (Full function - 800 lines)
 * FUNCTION calculate_bonus(emp_id NUMBER, dept_id NUMBER) RETURN NUMBER IS
 *   v_base NUMBER;
 *   -- 50 variable declarations
 * BEGIN
 *   -- 700 lines of complex logic
 *   RETURN v_base + v_bonus;
 * END calculate_bonus;
 *
 * -- Output (Stub - 4 lines)
 * FUNCTION calculate_bonus(emp_id NUMBER, dept_id NUMBER) RETURN NUMBER IS
 * BEGIN
 *   RETURN NULL;
 * END;
 * ```
 */
public class FunctionStubGenerator {

    private static final Logger log = LoggerFactory.getLogger(FunctionStubGenerator.class);

    /**
     * Generates a stub function by replacing the body with RETURN NULL or RETURN.
     *
     * The stub contains:
     * 1. Full signature (FUNCTION/PROCEDURE name, parameters, RETURN clause if applicable)
     * 2. Minimal body: BEGIN + RETURN NULL; (function) or RETURN; (procedure) + END;
     *
     * This allows ANTLR to extract metadata (name, parameters, return type) without parsing
     * the full implementation.
     *
     * @param fullFunctionSource Full function source (signature + body)
     * @param segment Function segment info (positions)
     * @return Stub function (signature + minimal body)
     */
    public String generateStub(String fullFunctionSource, PackageSegments.FunctionSegment segment) {
        log.trace("Generating stub for {}", segment.getName());

        // Extract signature part (from start to just before body)
        // The body starts after IS/AS keyword
        int signatureLength = segment.getBodyStartPos() - segment.getStartPos();
        String signature = fullFunctionSource.substring(0, signatureLength);

        // Trim trailing whitespace from signature
        signature = signature.trim();

        // Generate minimal body based on function vs procedure
        String stubBody;
        if (segment.isFunction()) {
            stubBody = " IS\nBEGIN\n  RETURN NULL;\nEND;";
        } else {
            stubBody = " IS\nBEGIN\n  RETURN;\nEND;";
        }

        String stub = signature + stubBody;

        log.trace("Generated stub ({} bytes) for {} (original: {} bytes)",
            stub.length(),
            segment.getName(),
            fullFunctionSource.length());

        return stub;
    }

    /**
     * Generates stubs for all functions in a package.
     *
     * @param cleanedPackageBody Package body with comments removed
     * @param segments Scanned function segments
     * @return Map of function name (lowercase) → stub source
     */
    public java.util.Map<String, String> generateAllStubs(String cleanedPackageBody,
                                                           PackageSegments segments) {
        log.debug("Generating stubs for {} functions", segments.getFunctionCount());

        java.util.Map<String, String> stubs = new java.util.HashMap<>();

        for (PackageSegments.FunctionSegment segment : segments.getFunctions()) {
            // Extract full function source
            String fullSource = cleanedPackageBody.substring(segment.getStartPos(), segment.getEndPos());

            // Generate stub
            String stub = generateStub(fullSource, segment);

            // Store with lowercase key
            stubs.put(segment.getName().toLowerCase(), stub);
        }

        log.debug("Generated {} stubs", stubs.size());
        return stubs;
    }
}
