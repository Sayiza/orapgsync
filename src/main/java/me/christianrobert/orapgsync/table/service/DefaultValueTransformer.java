package me.christianrobert.orapgsync.table.service;

import me.christianrobert.orapgsync.core.tools.CodeCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms Oracle column default values to PostgreSQL-compatible equivalents.
 *
 * <p>This transformer handles only the simplest, most common cases with straightforward mappings.
 * Complex PL/SQL expressions are left unmapped and flagged for manual review or future
 * automated transformation when PL/SQL functions can be parsed and translated.</p>
 *
 * <p>Transformation Strategy:</p>
 * <ul>
 *   <li>Simple function mappings: SYSDATE, SYS_GUID(), USER, etc.</li>
 *   <li>Sequence NEXTVAL syntax conversion</li>
 *   <li>Date/time function equivalents</li>
 *   <li>Complex expressions: Preserved in metadata, skipped in creation, logged as warnings</li>
 * </ul>
 */
public class DefaultValueTransformer {

    private static final Logger log = LoggerFactory.getLogger(DefaultValueTransformer.class);

    // Pattern to match Oracle sequence NEXTVAL: schema.sequence_name.NEXTVAL or sequence_name.NEXTVAL
    private static final Pattern SEQUENCE_NEXTVAL_PATTERN =
        Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\.NEXTVAL",
                       Pattern.CASE_INSENSITIVE);

    /**
     * Result of a default value transformation.
     */
    public static class TransformationResult {
        private final String transformedValue;
        private final boolean wasTransformed;
        private final String originalValue;
        private final String transformationNote;

        public TransformationResult(String transformedValue, boolean wasTransformed,
                                   String originalValue, String transformationNote) {
            this.transformedValue = transformedValue;
            this.wasTransformed = wasTransformed;
            this.originalValue = originalValue;
            this.transformationNote = transformationNote;
        }

        public String getTransformedValue() {
            return transformedValue;
        }

        public boolean wasTransformed() {
            return wasTransformed;
        }

        public String getOriginalValue() {
            return originalValue;
        }

        public String getTransformationNote() {
            return transformationNote;
        }

        public boolean isSkipped() {
            return transformedValue == null;
        }
    }

    /**
     * Transforms an Oracle default value to PostgreSQL equivalent.
     *
     * @param oracleDefault The Oracle default value expression (from data_default column)
     * @param columnName The column name (for logging purposes)
     * @param tableName The table name (for logging purposes)
     * @return TransformationResult containing the transformed value or null if skipped
     */
    public static TransformationResult transform(String oracleDefault, String columnName, String tableName) {
        if (oracleDefault == null || oracleDefault.trim().isEmpty()) {
            return new TransformationResult(null, false, oracleDefault, "No default value");
        }

        // Remove comments first (handles -- and /* */ comments)
        String withoutComments = CodeCleaner.removeComments(oracleDefault);
        String trimmed = withoutComments.trim();
        String original = oracleDefault.trim();

        // Simple literal values - pass through unchanged
        if (isSimpleLiteral(trimmed)) {
            return new TransformationResult(trimmed, false, original, "Simple literal");
        }

        // Try transformations in order of specificity

        // 1. SYSDATE -> CURRENT_TIMESTAMP
        if (trimmed.equalsIgnoreCase("SYSDATE")) {
            log.debug("Transformed SYSDATE -> CURRENT_TIMESTAMP for {}.{}", tableName, columnName);
            return new TransformationResult("CURRENT_TIMESTAMP", true, original,
                "SYSDATE -> CURRENT_TIMESTAMP");
        }

        // 2. SYS_GUID() -> gen_random_uuid()
        if (trimmed.equalsIgnoreCase("SYS_GUID()")) {
            log.debug("Transformed SYS_GUID() -> gen_random_uuid() for {}.{}", tableName, columnName);
            return new TransformationResult("gen_random_uuid()", true, original,
                "SYS_GUID() -> gen_random_uuid()");
        }

        // 3. USER -> current_user
        if (trimmed.equalsIgnoreCase("USER")) {
            log.debug("Transformed USER -> current_user for {}.{}", tableName, columnName);
            return new TransformationResult("current_user", true, original,
                "USER -> current_user");
        }

        // 4. Sequence NEXTVAL: schema.sequence.NEXTVAL -> nextval('schema.sequence')
        Matcher seqMatcher = SEQUENCE_NEXTVAL_PATTERN.matcher(trimmed);
        if (seqMatcher.matches()) {
            String schema = seqMatcher.group(1);
            String sequenceName = seqMatcher.group(2);

            String qualifiedName;
            if (schema != null) {
                // Remove trailing dot from schema
                schema = schema.substring(0, schema.length() - 1);
                qualifiedName = schema.toLowerCase() + "." + sequenceName.toLowerCase();
            } else {
                qualifiedName = sequenceName.toLowerCase();
            }

            String transformed = "nextval('" + qualifiedName + "')";
            log.debug("Transformed sequence NEXTVAL: {} -> {} for {}.{}",
                trimmed, transformed, tableName, columnName);
            return new TransformationResult(transformed, true, original,
                "Sequence NEXTVAL -> nextval()");
        }

        // 5. SYSTIMESTAMP -> CURRENT_TIMESTAMP
        if (trimmed.equalsIgnoreCase("SYSTIMESTAMP")) {
            log.debug("Transformed SYSTIMESTAMP -> CURRENT_TIMESTAMP for {}.{}", tableName, columnName);
            return new TransformationResult("CURRENT_TIMESTAMP", true, original,
                "SYSTIMESTAMP -> CURRENT_TIMESTAMP");
        }

        // 6. CURRENT_TIMESTAMP - Oracle also supports this (compatible)
        if (trimmed.equalsIgnoreCase("CURRENT_TIMESTAMP")) {
            return new TransformationResult("CURRENT_TIMESTAMP", false, original,
                "Compatible: CURRENT_TIMESTAMP");
        }

        // 7. CURRENT_DATE (both Oracle and PostgreSQL support)
        if (trimmed.equalsIgnoreCase("CURRENT_DATE")) {
            return new TransformationResult("CURRENT_DATE", false, original,
                "Compatible: CURRENT_DATE");
        }

        // 8. LOCALTIMESTAMP - Oracle and PostgreSQL compatible
        if (trimmed.equalsIgnoreCase("LOCALTIMESTAMP")) {
            return new TransformationResult("LOCALTIMESTAMP", false, original,
                "Compatible: LOCALTIMESTAMP");
        }

        // 9. SESSION_USER / CURRENT_USER - both supported in PostgreSQL
        if (trimmed.equalsIgnoreCase("SESSION_USER") || trimmed.equalsIgnoreCase("CURRENT_USER")) {
            String pgValue = trimmed.equalsIgnoreCase("SESSION_USER") ? "session_user" : "current_user";
            return new TransformationResult(pgValue, false, original,
                "Compatible: " + pgValue);
        }

        // 10. DBTIMEZONE / SESSIONTIMEZONE - Oracle specific, skip
        if (trimmed.equalsIgnoreCase("DBTIMEZONE") || trimmed.equalsIgnoreCase("SESSIONTIMEZONE")) {
            log.warn("Skipping Oracle timezone function for {}.{}: '{}' - no direct PostgreSQL equivalent",
                tableName, columnName, trimmed);
            return new TransformationResult(null, false, original,
                "Oracle timezone function - no direct PostgreSQL equivalent");
        }

        // 11. Empty string '' - convert to NULL in PostgreSQL (Oracle treats '' as NULL)
        if (trimmed.equals("''")) {
            log.debug("Transformed empty string '' -> NULL for {}.{}", tableName, columnName);
            return new TransformationResult("NULL", true, original,
                "Empty string -> NULL (Oracle semantics)");
        }

        // 12. NULL - explicit null default
        if (trimmed.equalsIgnoreCase("NULL")) {
            return new TransformationResult("NULL", false, original, "Explicit NULL");
        }

        // Complex expression - skip and warn
        log.warn("Skipping complex default value for {}.{}: '{}' - requires manual review or future PL/SQL transformation",
            tableName, columnName, trimmed);
        return new TransformationResult(null, false, original,
            "Complex expression - skipped (requires manual review)");
    }

    /**
     * Checks if a default value is a simple literal that can be passed through unchanged.
     * This includes: numbers, quoted strings, NULL
     */
    private static boolean isSimpleLiteral(String value) {
        // Numeric literal (including decimals, negatives, scientific notation)
        if (value.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            return true;
        }

        // String literal (single quoted)
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return true;
        }

        // Boolean-like literals
        if (value.equalsIgnoreCase("TRUE") || value.equalsIgnoreCase("FALSE")) {
            return true;
        }

        return false;
    }

    /**
     * Convenience method that returns just the transformed value or null if skipped.
     */
    public static String transformSimple(String oracleDefault, String columnName, String tableName) {
        return transform(oracleDefault, columnName, tableName).getTransformedValue();
    }
}
