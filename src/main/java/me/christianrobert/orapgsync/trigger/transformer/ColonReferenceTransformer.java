package me.christianrobert.orapgsync.trigger.transformer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms Oracle trigger correlation names to PostgreSQL equivalents.
 *
 * <p>Oracle uses :NEW and :OLD (with colons) to reference row values in triggers.
 * PostgreSQL uses NEW and OLD (without colons).</p>
 *
 * <p><strong>Examples:</strong></p>
 * <pre>
 * :NEW.salary → NEW.salary
 * :OLD.employee_id → OLD.employee_id
 * :new.status → new.status (case-preserving)
 * </pre>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * String oracleTriggerBody = "INSERT INTO audit VALUES (:NEW.id, :OLD.name);";
 * String postgresTriggerBody = ColonReferenceTransformer.removeColonReferences(oracleTriggerBody);
 * // Result: "INSERT INTO audit VALUES (NEW.id, OLD.name);"
 * </pre>
 *
 * <p><strong>Note:</strong> This transformer should be used AFTER removing comments
 * from the trigger body using {@code CodeCleaner.removeComments()}, as it does not
 * handle colons inside string literals or comments.</p>
 */
public class ColonReferenceTransformer {

    // Patterns for matching :NEW and :OLD with word boundaries
    private static final Pattern NEW_PATTERN = Pattern.compile("\\b:NEW\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_PATTERN = Pattern.compile("\\b:OLD\\b", Pattern.CASE_INSENSITIVE);

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
        if (plsqlCode == null || plsqlCode.isEmpty()) {
            return plsqlCode;
        }

        String result = plsqlCode;

        // Replace :NEW with NEW (case-preserving)
        result = replaceCasePreserving(result, NEW_PATTERN, "NEW");

        // Replace :OLD with OLD (case-preserving)
        result = replaceCasePreserving(result, OLD_PATTERN, "OLD");

        return result;
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
