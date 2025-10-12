package me.christianrobert.orapgsync.constraint.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates Oracle CHECK constraint expressions to PostgreSQL-compatible syntax.
 * Handles common Oracle functions that have different syntax in PostgreSQL.
 *
 * Supported translations:
 * - INSTR(str, substr) -> POSITION(substr IN str)
 * - INSTR(str, substr, 1, 1) -> POSITION(substr IN str)
 * - RAWTOHEX(value) -> UPPER(ENCODE(value::bytea, 'hex'))
 * - NVL(value, default) -> COALESCE(value, default)
 * - SUBSTR(str, start) -> SUBSTRING(str FROM start)
 * - SUBSTR(str, start, length) -> SUBSTRING(str FROM start FOR length)
 *
 * Note: Complex INSTR with custom start position or occurrence (not 1, 1)
 * are logged as warnings and returned unchanged, as they require custom functions.
 */
public class CheckConstraintTranslator {

    private static final Logger log = LoggerFactory.getLogger(CheckConstraintTranslator.class);

    /**
     * Translates an Oracle CHECK constraint condition to PostgreSQL syntax.
     *
     * @param oracleCondition The Oracle CHECK constraint condition
     * @return The translated PostgreSQL condition
     */
    public static String translate(String oracleCondition) {
        if (oracleCondition == null || oracleCondition.trim().isEmpty()) {
            return oracleCondition;
        }

        String translated = oracleCondition;

        // Apply translations in order
        translated = translateInstr(translated);
        translated = translateRawtohex(translated);
        translated = translateNvl(translated);
        translated = translateSubstr(translated);

        // Log if translation was applied
        if (!translated.equals(oracleCondition)) {
            log.info("Translated CHECK constraint: '{}' -> '{}'", oracleCondition, translated);
        }

        return translated;
    }

    /**
     * Translates Oracle INSTR function to PostgreSQL POSITION.
     *
     * Handles:
     * - INSTR(str, substr) -> POSITION(substr IN str)
     * - INSTR(str, substr, 1, 1) -> POSITION(substr IN str)
     *
     * Does NOT handle:
     * - INSTR with custom start position or occurrence (warns and returns unchanged)
     */
    private static String translateInstr(String condition) {
        // Pattern for INSTR with 2 parameters: INSTR(arg1, arg2)
        Pattern pattern2 = Pattern.compile(
                "INSTR\\s*\\(\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );

        // Pattern for INSTR with 4 parameters: INSTR(arg1, arg2, arg3, arg4)
        Pattern pattern4 = Pattern.compile(
                "INSTR\\s*\\(\\s*([^,]+?)\\s*,\\s*([^,]+?)\\s*,\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );

        // First, try to match 4-parameter version
        Matcher matcher4 = pattern4.matcher(condition);
        StringBuffer sb = new StringBuffer();

        while (matcher4.find()) {
            String str = matcher4.group(1).trim();
            String substr = matcher4.group(2).trim();
            String startPos = matcher4.group(3).trim();
            String occurrence = matcher4.group(4).trim();

            // Check if it's the simple case: INSTR(str, substr, 1, 1)
            if ("1".equals(startPos) && "1".equals(occurrence)) {
                // Can translate to POSITION
                String replacement = String.format("POSITION(%s IN %s)", substr, str);
                matcher4.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                log.debug("Translated INSTR({}, {}, 1, 1) to POSITION({} IN {})", str, substr, substr, str);
            } else {
                // Complex case - cannot translate easily, log warning
                log.warn("Cannot translate complex INSTR with start_position={}, occurrence={} in CHECK constraint. " +
                        "Consider creating a custom PostgreSQL function or adjusting the constraint.", startPos, occurrence);
                // Keep original
                matcher4.appendReplacement(sb, Matcher.quoteReplacement(matcher4.group(0)));
            }
        }
        matcher4.appendTail(sb);
        condition = sb.toString();

        // Now handle 2-parameter version: INSTR(str, substr)
        Matcher matcher2 = pattern2.matcher(condition);
        sb = new StringBuffer();

        while (matcher2.find()) {
            String str = matcher2.group(1).trim();
            String substr = matcher2.group(2).trim();

            // Simple 2-parameter INSTR can always be translated to POSITION
            String replacement = String.format("POSITION(%s IN %s)", substr, str);
            matcher2.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            log.debug("Translated INSTR({}, {}) to POSITION({} IN {})", str, substr, substr, str);
        }
        matcher2.appendTail(sb);

        return sb.toString();
    }

    /**
     * Translates Oracle RAWTOHEX function to PostgreSQL UPPER(ENCODE(..., 'hex')).
     *
     * Handles:
     * - RAWTOHEX(value) -> UPPER(ENCODE(value::bytea, 'hex'))
     *
     * Note: The ::bytea cast may need adjustment depending on the column type.
     */
    private static String translateRawtohex(String condition) {
        // Pattern for RAWTOHEX(arg)
        Pattern pattern = Pattern.compile(
                "RAWTOHEX\\s*\\(\\s*([^)]+?)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(condition);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String value = matcher.group(1).trim();

            // Translate to PostgreSQL ENCODE
            // Note: We cast to bytea, but this might need adjustment for text columns
            String replacement = String.format("UPPER(ENCODE(%s::bytea, 'hex'))", value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            log.debug("Translated RAWTOHEX({}) to UPPER(ENCODE({}::bytea, 'hex'))", value, value);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Translates Oracle NVL function to PostgreSQL COALESCE.
     *
     * Handles:
     * - NVL(value, default) -> COALESCE(value, default)
     *
     * Note: COALESCE is ANSI SQL standard and works in both Oracle and PostgreSQL.
     * It can handle more than 2 arguments, making it more flexible than NVL.
     */
    private static String translateNvl(String condition) {
        // Pattern for NVL(arg1, arg2)
        // This pattern handles nested parentheses by using a non-greedy match
        Pattern pattern = Pattern.compile(
                "NVL\\s*\\(\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(condition);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String value = matcher.group(1).trim();
            String defaultValue = matcher.group(2).trim();

            // Translate to PostgreSQL COALESCE
            String replacement = String.format("COALESCE(%s, %s)", value, defaultValue);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            log.debug("Translated NVL({}, {}) to COALESCE({}, {})", value, defaultValue, value, defaultValue);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Translates Oracle SUBSTR function to PostgreSQL SUBSTRING.
     *
     * Handles:
     * - SUBSTR(str, start) -> SUBSTRING(str FROM start)
     * - SUBSTR(str, start, length) -> SUBSTRING(str FROM start FOR length)
     *
     * Note: Oracle uses 1-based indexing for SUBSTR, PostgreSQL SUBSTRING also uses 1-based indexing,
     * so no index adjustment is needed.
     */
    private static String translateSubstr(String condition) {
        // Pattern for SUBSTR with 3 parameters: SUBSTR(str, start, length)
        Pattern pattern3 = Pattern.compile(
                "SUBSTR\\s*\\(\\s*([^,]+?)\\s*,\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );

        // Pattern for SUBSTR with 2 parameters: SUBSTR(str, start)
        Pattern pattern2 = Pattern.compile(
                "SUBSTR\\s*\\(\\s*([^,]+?)\\s*,\\s*([^)]+?)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );

        // First, handle 3-parameter version (must be done first to avoid matching as 2-param)
        Matcher matcher3 = pattern3.matcher(condition);
        StringBuffer sb = new StringBuffer();

        while (matcher3.find()) {
            String str = matcher3.group(1).trim();
            String start = matcher3.group(2).trim();
            String length = matcher3.group(3).trim();

            // Translate to PostgreSQL SUBSTRING with FROM and FOR
            String replacement = String.format("SUBSTRING(%s FROM %s FOR %s)", str, start, length);
            matcher3.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            log.debug("Translated SUBSTR({}, {}, {}) to SUBSTRING({} FROM {} FOR {})",
                    str, start, length, str, start, length);
        }
        matcher3.appendTail(sb);
        condition = sb.toString();

        // Now handle 2-parameter version: SUBSTR(str, start)
        Matcher matcher2 = pattern2.matcher(condition);
        sb = new StringBuffer();

        while (matcher2.find()) {
            String str = matcher2.group(1).trim();
            String start = matcher2.group(2).trim();

            // Translate to PostgreSQL SUBSTRING with FROM only
            String replacement = String.format("SUBSTRING(%s FROM %s)", str, start);
            matcher2.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            log.debug("Translated SUBSTR({}, {}) to SUBSTRING({} FROM {})", str, start, str, start);
        }
        matcher2.appendTail(sb);

        return sb.toString();
    }

    /**
     * Checks if a CHECK constraint condition contains Oracle functions that cannot be translated.
     *
     * @param condition The CHECK constraint condition
     * @return true if the condition contains untranslatable Oracle functions
     */
    public static boolean containsUntranslatableFunctions(String condition) {
        if (condition == null) {
            return false;
        }

        // Check for complex INSTR (with non-1,1 parameters)
        Pattern instrPattern = Pattern.compile(
                "INSTR\\s*\\([^,]+,[^,]+,\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher instrMatcher = instrPattern.matcher(condition);
        while (instrMatcher.find()) {
            String startPos = instrMatcher.group(1).trim();
            String occurrence = instrMatcher.group(2).trim();
            if (!"1".equals(startPos) || !"1".equals(occurrence)) {
                return true;
            }
        }

        // Add other untranslatable functions here as needed
        // Examples: DECODE (complex cases), NVL2, REGEXP_LIKE (some cases), etc.

        return false;
    }
}
