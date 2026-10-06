package me.christianrobert.orapgsync.transformer.parser;

import me.christianrobert.orapgsync.transformer.parser.TypeBodySegments.TypeMethodSegment;

/**
 * Generates stub methods (signature + empty body) for fast ANTLR parsing during extraction.
 *
 * Stubs are used to extract metadata (method names, parameters, return types) without parsing
 * the full method body implementation, reducing parse time from minutes to milliseconds.
 *
 * **Stub Format:**
 * - Functions: `signature IS BEGIN RETURN NULL; END;`
 * - Procedures: `signature IS BEGIN RETURN; END;`
 *
 * **Usage:**
 * ```java
 * String fullMethodSource = typeBodySql.substring(segment.startPos, segment.endPos);
 * TypeMethodStubGenerator stubGen = new TypeMethodStubGenerator();
 * String stubSource = stubGen.generateStub(fullMethodSource, segment);
 * ```
 */
public class TypeMethodStubGenerator {

    /**
     * Generates a stub method by replacing body with RETURN NULL/RETURN.
     *
     * Keeps Oracle syntax (implicit SELF for member methods).
     *
     * @param fullMethodSource Full method source (signature + body)
     * @param segment Method segment info (positions)
     * @return Stub method (signature + "RETURN NULL;" or "RETURN;")
     */
    public String generateStub(String fullMethodSource, TypeMethodSegment segment) {
        // Calculate relative position of body start within the full method source
        int signatureEnd = segment.getBodyStartPos() - segment.getStartPos();

        // Extract signature part (before IS/AS)
        String signature;
        if (signatureEnd > 0 && signatureEnd <= fullMethodSource.length()) {
            signature = fullMethodSource.substring(0, signatureEnd).trim();
        } else {
            // Fallback: use entire source (shouldn't happen with correct segment data)
            signature = fullMethodSource.trim();
        }

        // Generate stub body based on method type
        String stubBody;
        if (segment.isFunction()) {
            stubBody = " IS\nBEGIN\n  RETURN NULL;\nEND;";
        } else {
            stubBody = " IS\nBEGIN\n  RETURN;\nEND;";
        }

        return signature + stubBody;
    }
}
