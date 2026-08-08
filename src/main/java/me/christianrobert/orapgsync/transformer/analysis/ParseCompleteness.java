package me.christianrobert.orapgsync.transformer.analysis;

import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Detects Oracle source that the parser stopped reading before the end.
 *
 * <p>The grammar entry rules ({@code select_statement}, {@code function_body}, ...) are not
 * anchored to EOF. When the parser reaches a construct it cannot fit into the rule, it can end
 * the rule early and leave the rest of the input unread <strong>without reporting an error</strong>.
 * The transformation then succeeds on the fragment that was parsed, and everything after it is
 * silently gone.</p>
 *
 * <p>Example: in {@code SELECT ... FROM sales_view MODEL PARTITION BY ...} the parser reads
 * {@code MODEL} as a table alias, ends the statement there and drops the whole MODEL clause.</p>
 */
public final class ParseCompleteness {

    private static final int MAX_TAIL_SNIPPET = 120;

    private ParseCompleteness() {
    }

    /**
     * Returns the significant source text after the last token the parser consumed.
     *
     * @param parseResult result of a successful parse
     * @return the unconsumed tail, or null if the parser read the whole source
     */
    public static String unconsumedTail(ParseResult parseResult) {
        if (parseResult == null || parseResult.getTree() == null) {
            return null;
        }

        String source = parseResult.getOriginalSql();
        ParserRuleContext tree = parseResult.getTree();
        if (source == null || tree.getStop() == null) {
            return null;
        }

        int lastConsumedIndex = tree.getStop().getStopIndex();
        if (lastConsumedIndex < 0 || lastConsumedIndex >= source.length() - 1) {
            return null;
        }

        String tail = source.substring(lastConsumedIndex + 1);
        return isInsignificant(tail) ? null : tail.trim();
    }

    /**
     * First word of the unconsumed tail — usually the keyword the grammar could not place, which
     * makes it a good grouping key for the compatibility report.
     */
    public static String firstUnconsumedToken(String tail) {
        if (tail == null || tail.isBlank()) {
            return null;
        }
        String[] words = tail.trim().split("\\s+", 2);
        return words[0];
    }

    /** Shortened tail for display in a report. */
    public static String snippet(String tail) {
        if (tail == null) {
            return null;
        }
        String normalized = tail.replaceAll("\\s+", " ").trim();
        return normalized.length() > MAX_TAIL_SNIPPET
                ? normalized.substring(0, MAX_TAIL_SNIPPET) + " ..."
                : normalized;
    }

    /**
     * Trailing whitespace, statement terminators and comments are not a truncation — Oracle stores
     * view text with those and the parser is right to stop before them.
     */
    private static boolean isInsignificant(String tail) {
        String stripped = tail
                .replaceAll("/\\*.*?\\*/", " ")   // block comments
                .replaceAll("--[^\\n\\r]*", " ")  // line comments
                .replace(";", " ")
                .replace("/", " ");
        return stripped.isBlank();
    }
}
