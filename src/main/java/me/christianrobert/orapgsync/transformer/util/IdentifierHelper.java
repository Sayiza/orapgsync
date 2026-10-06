package me.christianrobert.orapgsync.transformer.util;

import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.regex.Pattern;

/**
 * Canonicalization and emission of Oracle identifiers during transformation.
 *
 * <h2>Why this exists</h2>
 * Oracle's grammar has two identifier forms and the parser hands both to us verbatim:
 * <ul>
 *   <li>{@code regular_id} — {@code RUN_ID}, case-insensitive, Oracle stores it upper-cased</li>
 *   <li>{@code DELIMITED_ID} — {@code "RUN_ID"}, case-sensitive, stored exactly as written</li>
 * </ul>
 *
 * <p>Passing that raw text through causes two distinct failures, and both were live bugs:
 *
 * <ol>
 *   <li><b>Emission.</b> {@code "RUN_ID"} reaches PostgreSQL as a quoted, case-sensitive name,
 *       but the DDL migration created the column as {@code run_id}. Result:
 *       {@code column "RUN_ID" does not exist}.</li>
 *   <li><b>Lookup.</b> Metadata indices are lower-case keyed, and callers key them with
 *       {@code text.toLowerCase()}. For {@code "RUN_ID"} that yields {@code "run_id"} with the
 *       quote characters still attached, so every lookup misses — synonym resolution, CTE
 *       detection, column type inference and package-variable rewriting all silently take the
 *       wrong branch.</li>
 * </ol>
 *
 * <h2>The two operations</h2>
 * <ul>
 *   <li>{@link #canonical} — the <i>logical Oracle name</i>: delimiters removed, {@code ""}
 *       un-escaped, upper-cased. Use for every lookup, comparison and index key.</li>
 *   <li>{@link #emit} — the <i>PostgreSQL name</i>, produced by
 *       {@link PostgresIdentifierNormalizer}. Use for everything that lands in output SQL.
 *       Unquoted identifiers pass through untouched; see {@link #emit(String)} for why.</li>
 * </ul>
 *
 * <p>{@code emit} delegates to {@link PostgresIdentifierNormalizer} rather than re-deriving the
 * rule, and that is the whole point: it is the same class the DDL jobs use to create tables,
 * views, constraints and indexes. Whatever it does — including its curated and deliberately
 * incomplete reserved-word list — is correct here by construction, because it is what named the
 * objects being referenced. An independently written rule could drift from it; this cannot.
 *
 * <h2>Known gap</h2>
 * An unquoted Oracle column genuinely named {@code USER} still emits as bare {@code USER}, which
 * PostgreSQL reads as the current-user keyword rather than the column. Distinguishing the two
 * needs column metadata at the reference site, so it is left as-is — pre-existing behaviour,
 * neither fixed nor worsened here.
 *
 * <h2>Case folding and its one casualty</h2>
 * {@code canonical} upper-cases, so Oracle {@code "Foo"} and {@code "FOO"} collapse to the same
 * name. That conflation is inherited, not introduced — {@code PostgresTableCreationJob} already
 * lower-cases every column name, so a table with two such columns cannot migrate correctly at
 * all. Rather than let it corrupt silently, the creation jobs reject it up front; see
 * {@link PostgresIdentifierNormalizer#findCollisions}.
 *
 * @see PostgresIdentifierNormalizer
 */
public final class IdentifierHelper {

    private IdentifierHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Returns the logical Oracle name for a parsed identifier: delimiters removed, embedded
     * {@code ""} un-escaped, upper-cased.
     *
     * <p>Upper-casing makes the result the form Oracle itself stores for unquoted identifiers,
     * so it matches {@code ALL_TAB_COLUMNS.COLUMN_NAME} and friends directly. Existing call
     * sites that follow up with {@code .toLowerCase()} or {@code .toUpperCase()} remain correct.
     *
     * <pre>
     * run_id      → RUN_ID
     * RUN_ID      → RUN_ID
     * "RUN_ID"    → RUN_ID
     * "MyCol"     → MYCOL
     * "a""b"      → A"B
     * </pre>
     *
     * @param ctx identifier context from the parser ({@code id_expression}, {@code identifier}, …)
     * @return the canonical Oracle name, or {@code null} if {@code ctx} is {@code null}
     */
    public static String canonical(ParserRuleContext ctx) {
        return ctx == null ? null : canonical(ctx.getText());
    }

    /**
     * String form of {@link #canonical(ParserRuleContext)}, for text already pulled from the tree.
     *
     * @param rawText raw identifier text, quoted or not
     * @return the canonical Oracle name
     */
    public static String canonical(String rawText) {
        String unquoted = unquote(rawText);
        return unquoted == null ? null : unquoted.toUpperCase();
    }

    /**
     * Returns the PostgreSQL identifier for a parsed Oracle identifier — the name the DDL
     * migration gave the corresponding object.
     *
     * <pre>
     * run_id      → run_id
     * "RUN_ID"    → run_id
     * "TIMESTAMP" → "timestamp"   (reserved word)
     * "Order#"    → "order#"      (special character)
     * </pre>
     *
     * @param ctx identifier context from the parser
     * @return the PostgreSQL identifier, quoted only where PostgreSQL requires it
     */
    public static String emit(ParserRuleContext ctx) {
        return ctx == null ? null : emit(ctx.getText());
    }

    /**
     * String form of {@link #emit(ParserRuleContext)}.
     *
     * <p><b>Unquoted identifiers are returned untouched.</b> That is deliberate, and it is what
     * makes this change incapable of regressing a working transformation: an unquoted Oracle
     * identifier is case-insensitive, and emitting it verbatim lets PostgreSQL fold it to the
     * lower-case name the DDL migration created — which is the behaviour that already worked.
     * Normalizing it instead would quote it whenever it hit the reserved-word list, and turn
     * bare {@code USER} into {@code "user"} and {@code LEVEL} into {@code "level"}, destroying
     * their PostgreSQL keyword meaning.
     *
     * <p>Two cases therefore get rewritten, and only two:
     * <ol>
     *   <li><b>Quoted</b> ({@code DELIMITED_ID}) — the reported bug. {@code "RUN_ID"} is
     *       case-sensitive in PostgreSQL and does not match the migrated {@code run_id}.</li>
     *   <li><b>Unquoted but not a plain identifier</b> — Oracle permits {@code #} and {@code $}
     *       unquoted, PostgreSQL does not. Emitting these verbatim is a syntax error, so
     *       normalizing can only improve on it.</li>
     * </ol>
     *
     * @param rawText raw identifier text as it appeared in the Oracle source
     * @return the PostgreSQL identifier
     */
    public static String emit(String rawText) {
        if (rawText == null) {
            return null;
        }
        String unquoted = unquote(rawText);
        if (!isQuoted(rawText) && PLAIN_IDENTIFIER.matcher(unquoted).matches()) {
            return unquoted;
        }
        return PostgresIdentifierNormalizer.normalizeIdentifier(unquoted);
    }

    /**
     * An identifier PostgreSQL can read unquoted, case-folding it to the migrated name.
     *
     * <p>Underscore-leading names are included; digit-leading and anything containing
     * {@code #}, {@code $} or whitespace are not, because PostgreSQL rejects them bare.
     */
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Strips {@code DELIMITED_ID} delimiters and un-escapes doubled quotes, leaving case alone.
     *
     * <p>Unquoted text is returned unchanged — notably <i>not</i> case-folded, so this is the
     * right primitive when the caller needs to know how the name was actually written.
     *
     * @param rawText raw identifier text, quoted or not
     * @return the bare identifier name
     */
    public static String unquote(String rawText) {
        if (rawText == null) {
            return null;
        }
        String trimmed = rawText.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"'
                || trimmed.charAt(trimmed.length() - 1) != '"') {
            return trimmed;
        }
        // Oracle escapes a literal double quote inside a delimited identifier by doubling it.
        return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
    }

    /**
     * Reports whether the parsed text is a {@code DELIMITED_ID} (a quoted identifier).
     *
     * @param rawText raw identifier text
     * @return {@code true} if the text is delimited by double quotes
     */
    public static boolean isQuoted(String rawText) {
        if (rawText == null) {
            return false;
        }
        String trimmed = rawText.trim();
        return trimmed.length() >= 2 && trimmed.charAt(0) == '"'
                && trimmed.charAt(trimmed.length() - 1) == '"';
    }
}
