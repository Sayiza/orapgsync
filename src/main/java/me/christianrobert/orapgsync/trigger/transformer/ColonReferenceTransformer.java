package me.christianrobert.orapgsync.trigger.transformer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms Oracle trigger correlation names to PostgreSQL equivalents.
 *
 * <p>Oracle uses :NEW and :OLD (with colons) to reference row values in triggers.
 * PostgreSQL uses NEW and OLD (without colons).</p>
 *
 * <p><strong>Standard Examples:</strong></p>
 * <pre>
 * :NEW.salary → NEW.salary
 * :OLD.employee_id → OLD.employee_id
 * :new.status → new.status (case-preserving)
 * </pre>
 *
 * <p><strong>Custom Alias Examples (via REFERENCING clause):</strong></p>
 * <pre>
 * -- Oracle: REFERENCING NEW AS pNew OLD AS pOld
 * :pNew.salary → NEW.salary
 * :pOld.employee_id → OLD.employee_id
 * </pre>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * // Standard NEW/OLD
 * String result = ColonReferenceTransformer.removeColonReferences(code);
 *
 * // Custom aliases (e.g., REFERENCING NEW AS pNew OLD AS pOld)
 * String result = ColonReferenceTransformer.removeColonReferences(code, "PNEW", "POLD");
 * </pre>
 *
 * <p><strong>Note:</strong> This transformer should be used AFTER removing comments
 * from the trigger body using {@code CodeCleaner.removeComments()}, as it does not
 * handle colons inside string literals or comments.</p>
 */
public class ColonReferenceTransformer {

    // Patterns for matching :NEW and :OLD
    // Uses negative lookbehind to ensure no word character before the colon
    // and word boundary after to ensure complete token match
    // Matches: :NEW.column, :NEW , (:NEW, but NOT :NEWRECORD
    private static final Pattern NEW_PATTERN = Pattern.compile("(?<!\\w):NEW\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_PATTERN = Pattern.compile("(?<!\\w):OLD\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Removes colons from :NEW and :OLD references in Oracle trigger PL/SQL code.
     *
     * <p>This method performs a case-preserving replacement:
     * <ul>
     *   <li>:NEW → NEW</li>
     *   <li>:new → new</li>
     *   <li>:New → New</li>
     *   <li>:OLD → OLD</li>
     *   <li>:old → old</li>
     *   <li>:Old → Old</li>
     * </ul>
     *
     * <p>The replacement uses word boundaries to ensure that only complete
     * :NEW/:OLD tokens are replaced, not substrings within other identifiers.</p>
     *
     * @param plsqlCode Oracle PL/SQL trigger body (with colons)
     * @return PostgreSQL PL/pgSQL trigger body (without colons)
     */
    public static String removeColonReferences(String plsqlCode) {
        return removeColonReferences(plsqlCode, "NEW", "OLD");
    }

    /**
     * Removes colons from :NEW/:OLD references, supporting custom aliases from REFERENCING clause.
     *
     * <p>Oracle's REFERENCING clause allows custom aliases:</p>
     * <pre>
     * REFERENCING NEW AS pNew OLD AS pOld
     * </pre>
     *
     * <p>This method transforms these custom aliases to PostgreSQL's standard NEW/OLD:</p>
     * <ul>
     *   <li>:pNew → NEW (when newAlias="PNEW")</li>
     *   <li>:pOld → OLD (when oldAlias="POLD")</li>
     *   <li>:pNew.column → NEW.column</li>
     *   <li>:pOld.column → OLD.column</li>
     * </ul>
     *
     * @param plsqlCode Oracle PL/SQL trigger body (with colons)
     * @param newAlias The alias used for NEW (e.g., "PNEW", "NEW", "N")
     * @param oldAlias The alias used for OLD (e.g., "POLD", "OLD", "O")
     * @return PostgreSQL PL/pgSQL trigger body with standard NEW/OLD references
     */
    public static String removeColonReferences(String plsqlCode, String newAlias, String oldAlias) {
        if (plsqlCode == null || plsqlCode.isEmpty()) {
            return plsqlCode;
        }

        String result = plsqlCode;

        // Normalize aliases (handle null/empty)
        String effectiveNewAlias = (newAlias != null && !newAlias.isEmpty()) ? newAlias : "NEW";
        String effectiveOldAlias = (oldAlias != null && !oldAlias.isEmpty()) ? oldAlias : "OLD";

        // If using custom aliases, replace them first
        if (!"NEW".equalsIgnoreCase(effectiveNewAlias)) {
            // Replace :customNewAlias with NEW
            // Pattern: no word char before colon, alias, word boundary after
            Pattern customNewPattern = Pattern.compile(
                    "(?<!\\w):" + Pattern.quote(effectiveNewAlias) + "\\b",
                    Pattern.CASE_INSENSITIVE);
            result = replaceWithTarget(result, customNewPattern, "NEW");
        }

        if (!"OLD".equalsIgnoreCase(effectiveOldAlias)) {
            // Replace :customOldAlias with OLD
            // Pattern: no word char before colon, alias, word boundary after
            Pattern customOldPattern = Pattern.compile(
                    "(?<!\\w):" + Pattern.quote(effectiveOldAlias) + "\\b",
                    Pattern.CASE_INSENSITIVE);
            result = replaceWithTarget(result, customOldPattern, "OLD");
        }

        // Also handle standard :NEW and :OLD (in case they're mixed or it's the default)
        result = replaceCasePreserving(result, NEW_PATTERN, "NEW");
        result = replaceCasePreserving(result, OLD_PATTERN, "OLD");

        return result;
    }

    /**
     * Replaces all occurrences of a pattern with the target string.
     * Used for custom alias replacement where we always want uppercase NEW/OLD.
     *
     * @param text Text to transform
     * @param pattern Pattern to match
     * @param target Target replacement (e.g., "NEW" or "OLD")
     * @return Transformed text
     */
    private static String replaceWithTarget(String text, Pattern pattern, String target) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(target);
    }

    /**
     * Performs case-preserving replacement using a regex pattern.
     *
     * <p>This method matches the pattern and replaces each match by removing
     * the colon while preserving the original case of the letters.</p>
     *
     * @param text Text to transform
     * @param pattern Pattern to match (e.g., :NEW)
     * @param replacement Base replacement text (e.g., "NEW")
     * @return Transformed text
     */
    private static String replaceCasePreserving(String text, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String matched = matcher.group();
            // Remove the colon and preserve the case of the original match
            String replaced = preserveCase(matched.substring(1), replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Preserves the case pattern of the original text when applying a replacement.
     *
     * <p>Examples:
     * <ul>
     *   <li>preserveCase("NEW", "NEW") → "NEW"</li>
     *   <li>preserveCase("new", "NEW") → "new"</li>
     *   <li>preserveCase("New", "NEW") → "New"</li>
     * </ul>
     *
     * @param original Original text (e.g., "new")
     * @param replacement Base replacement text (e.g., "NEW")
     * @return Case-preserved replacement
     */
    private static String preserveCase(String original, String replacement) {
        if (original.length() != replacement.length()) {
            // If lengths don't match, just return the original case pattern as best we can
            return matchCase(original, replacement);
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < original.length(); i++) {
            char origChar = original.charAt(i);
            char replChar = replacement.charAt(i);

            if (Character.isUpperCase(origChar)) {
                result.append(Character.toUpperCase(replChar));
            } else if (Character.isLowerCase(origChar)) {
                result.append(Character.toLowerCase(replChar));
            } else {
                result.append(replChar);
            }
        }

        return result.toString();
    }

    /**
     * Matches the overall case pattern (all upper, all lower, or title case).
     *
     * @param original Original text
     * @param replacement Replacement text
     * @return Case-matched replacement
     */
    private static String matchCase(String original, String replacement) {
        if (original.isEmpty()) {
            return replacement;
        }

        // Check if all uppercase
        if (original.equals(original.toUpperCase())) {
            return replacement.toUpperCase();
        }

        // Check if all lowercase
        if (original.equals(original.toLowerCase())) {
            return replacement.toLowerCase();
        }

        // Check if title case (first letter uppercase, rest lowercase)
        if (Character.isUpperCase(original.charAt(0)) &&
            original.substring(1).equals(original.substring(1).toLowerCase())) {
            return Character.toUpperCase(replacement.charAt(0)) +
                   replacement.substring(1).toLowerCase();
        }

        // Default: return as-is
        return replacement;
    }
}
