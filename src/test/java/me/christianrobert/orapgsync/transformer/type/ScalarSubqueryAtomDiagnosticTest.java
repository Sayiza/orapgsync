package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to investigate the Atom/Subquery type propagation issue.
 *
 * <p>This test walks the AST to find specific nodes and checks their types.</p>
 */
class ScalarSubqueryAtomDiagnosticTest {

    private AntlrParser parser;
    private TransformationIndices indices;
    private Map<String, TypeInfo> typeCache;
    private TypeAnalysisVisitor visitor;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        indices = new TransformationIndices(
                new HashMap<>(),
                new HashMap<>(),
                new HashSet<>(),
                new HashMap<>(),
                Collections.emptyMap(),
                Collections.emptySet()
        );

        typeCache = new HashMap<>();
        visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
    }

    @Test
    void diagnoseAtomSubqueryTypePropagation() {
        // Given: Scalar subquery with parentheses - (SELECT 1 FROM dual)
        String sql = "SELECT (SELECT 1 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Walk the AST and find the relevant nodes
        System.out.println("\n=== AST Structure and Types ===");

        AtomAndSubqueryFinder finder = new AtomAndSubqueryFinder();
        finder.visit(parseResult.getTree());

        System.out.println("\nFound Subquery nodes: " + finder.subqueryCount);
        System.out.println("Found Atom nodes: " + finder.atomCount);

        // Check types of found nodes
        if (finder.subqueryContext != null) {
            String subqueryKey = visitor.nodeKey(finder.subqueryContext);
            TypeInfo subqueryType = typeCache.get(subqueryKey);
            System.out.println("\nSubquery type: " + (subqueryType != null ? subqueryType.getCategory() : "NOT CACHED"));
        }

        if (finder.atomContext != null) {
            String atomKey = visitor.nodeKey(finder.atomContext);
            TypeInfo atomType = typeCache.get(atomKey);
            System.out.println("Atom (wrapping subquery) type: " + (atomType != null ? atomType.getCategory() : "NOT CACHED"));
        }

        System.out.println("\n=== All Cache Entries ===");
        typeCache.forEach((key, type) -> {
            System.out.println("Position " + key + " -> " + type.getCategory());
        });
        System.out.println("===========================\n");

        // Assertions
        assertNotNull(finder.subqueryContext, "Should find a Subquery node");
        assertNotNull(finder.atomContext, "Should find an Atom node wrapping the subquery");

        String subqueryKey = visitor.nodeKey(finder.subqueryContext);
        TypeInfo subqueryType = typeCache.get(subqueryKey);
        assertNotNull(subqueryType, "Subquery should have cached type");
        assertTrue(subqueryType.isNumeric(), "Subquery should be NUMERIC, but was: " + subqueryType.getCategory());

        String atomKey = visitor.nodeKey(finder.atomContext);
        TypeInfo atomType = typeCache.get(atomKey);
        assertNotNull(atomType, "Atom should have cached type");

        // THIS IS THE KEY ASSERTION - currently fails
        assertTrue(atomType.isNumeric(),
            "Atom wrapping scalar subquery should be NUMERIC, but was: " + atomType.getCategory());
    }

    /**
     * Helper visitor to find Atom and Subquery nodes in the AST.
     */
    private static class AtomAndSubqueryFinder extends org.antlr.v4.runtime.tree.AbstractParseTreeVisitor<Void> {
        int subqueryCount = 0;
        int atomCount = 0;
        PlSqlParser.SubqueryContext subqueryContext = null;
        PlSqlParser.AtomContext atomContext = null;

        @Override
        public Void visit(ParseTree tree) {
            if (tree instanceof PlSqlParser.SubqueryContext) {
                subqueryCount++;
                if (subqueryContext == null) {
                    subqueryContext = (PlSqlParser.SubqueryContext) tree;
                    System.out.println("Found Subquery at position: " + getPosition(subqueryContext));
                }
            }

            if (tree instanceof PlSqlParser.AtomContext) {
                PlSqlParser.AtomContext atom = (PlSqlParser.AtomContext) tree;
                // Check if this atom contains a subquery
                if (atom.subquery() != null) {
                    atomCount++;
                    if (atomContext == null) {
                        atomContext = atom;
                        System.out.println("Found Atom (with subquery) at position: " + getPosition(atom));
                    }
                }
            }

            return visitAllChildren(tree);
        }

        public Void visitAllChildren(ParseTree tree) {
            if (tree == null) {
                return null;
            }
            for (int i = 0; i < tree.getChildCount(); i++) {
                ParseTree child = tree.getChild(i);
                if (child != null) {
                    visit(child);
                }
            }
            return null;
        }

        private String getPosition(ParserRuleContext ctx) {
            if (ctx.start == null || ctx.stop == null) {
                return "unknown";
            }
            return ctx.start.getStartIndex() + ":" + ctx.stop.getStopIndex();
        }
    }
}
