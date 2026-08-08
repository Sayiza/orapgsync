package me.christianrobert.orapgsync.transformer.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a parse tree and reports every occurrence of a construct listed in the
 * {@link ConstructCatalog}.
 *
 * <p>This is deliberately independent of the transformer: it answers "which Oracle constructs are
 * in this source" regardless of whether the transformation succeeded. That is what makes silent
 * losses visible — a view can transform without an error and still have lost its PIVOT.</p>
 */
public final class ConstructDetector {

    private static final int MAX_SNIPPET_LENGTH = 120;

    private ConstructDetector() {
    }

    /**
     * Detects catalogued constructs in the given parse tree.
     *
     * @param tree parse tree, may be null (a failed parse produces no detections)
     * @return detected constructs in source order; empty if the tree is null
     */
    public static List<DetectedConstruct> detect(ParseTree tree) {
        List<DetectedConstruct> detected = new ArrayList<>();
        if (tree != null) {
            walk(tree, detected);
        }
        return detected;
    }

    private static void walk(ParseTree node, List<DetectedConstruct> detected) {
        if (node instanceof ParserRuleContext ctx) {
            for (ConstructRule rule : ConstructCatalog.rulesFor(ctx.getClass())) {
                if (rule.matches(ctx)) {
                    detected.add(toDetection(rule, ctx));
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), detected);
        }
    }

    private static DetectedConstruct toDetection(ConstructRule rule, ParserRuleContext ctx) {
        int line = ctx.getStart() != null ? ctx.getStart().getLine() : 0;
        return new DetectedConstruct(
                rule.id(),
                rule.displayName(),
                ConstructCatalog.supportOf(rule),
                line,
                snippetOf(ctx),
                rule.note());
    }

    /**
     * Extracts the original source text of the context, including whitespace, truncated to a
     * length that is useful in a report.
     */
    private static String snippetOf(ParserRuleContext ctx) {
        if (ctx.getStart() == null || ctx.getStop() == null || ctx.getStart().getInputStream() == null) {
            return ctx.getText();
        }

        Interval interval = Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex());
        String text = ctx.getStart().getInputStream().getText(interval);
        String normalized = text.replaceAll("\\s+", " ").trim();

        return normalized.length() > MAX_SNIPPET_LENGTH
                ? normalized.substring(0, MAX_SNIPPET_LENGTH) + " ..."
                : normalized;
    }
}
