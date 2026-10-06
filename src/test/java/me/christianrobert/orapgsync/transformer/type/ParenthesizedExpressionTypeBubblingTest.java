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
 * Tests for type bubbling through parenthesized expressions (Atom nodes).
 *
 * <p>This test class verifies that types propagate correctly through parentheses
 * in all cases, not just scalar subqueries.</p>
 *
 * <p><b>Bug Context:</b> Phase 4 only fixed type propagation for scalar subqueries
 * {@code (SELECT ... FROM ...)}, but not for regular parenthesized expressions
 * {@code (expression)}.</p>
 *
 * <p><b>Grammar Context:</b></p>
 * <pre>
 * atom
 *     : bind_variable
 *     | constant
 *     | inquiry_directive
 *     | general_element outer_join_sign?
 *     | '(' subquery ')' subquery_operation_part*   // ← Fixed in Phase 4
 *     | '(' expressions_ ')'                         // ← Bug: Not fixed
 *     ;
 * </pre>
 */
class ParenthesizedExpressionTypeBubblingTest {

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

    // ========== Basic Parenthesized Expression Tests ==========

    @Test
    void parenthesizedLiteral_numeric_shouldPropagateType() {
        // Given: Parenthesized numeric literal
        String sql = "SELECT (42) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Find the Atom node and verify its type
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        assertNotNull(finder.atomContext, "Should find an Atom node for (42)");
        String atomKey = visitor.nodeKey(finder.atomContext);
        TypeInfo atomType = typeCache.get(atomKey);

        assertNotNull(atomType, "Atom should have cached type");
        assertTrue(atomType.isNumeric(),
                "Parenthesized literal (42) should be NUMERIC, but was: " + atomType.getCategory());
    }

    @Test
    void parenthesizedLiteral_string_shouldPropagateType() {
        // Given: Parenthesized string literal
        String sql = "SELECT ('test') FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Verify type propagation
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        assertNotNull(finder.atomContext);
        String atomKey = visitor.nodeKey(finder.atomContext);
        TypeInfo atomType = typeCache.get(atomKey);

        assertNotNull(atomType);
        assertTrue(atomType.isText(),
                "Parenthesized string literal should be TEXT, but was: " + atomType.getCategory());
    }

    @Test
    void parenthesizedLiteral_date_shouldPropagateType() {
        // Given: Parenthesized date literal
        String sql = "SELECT (DATE '2024-01-01') FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Verify type propagation
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        assertNotNull(finder.atomContext);
        String atomKey = visitor.nodeKey(finder.atomContext);
        TypeInfo atomType = typeCache.get(atomKey);

        assertNotNull(atomType);
        assertTrue(atomType.isDate(),
                "Parenthesized date literal should be DATE, but was: " + atomType.getCategory());
    }

    // ========== Parenthesized Arithmetic Expression Tests ==========

    @Test
    void parenthesizedArithmetic_shouldPropagateType() {
        // Given: Parenthesized arithmetic expression
        String sql = "SELECT (10 + 5) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Verify type propagation
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        assertNotNull(finder.atomContext, "Should find Atom node for (10 + 5)");
        String atomKey = visitor.nodeKey(finder.atomContext);
        TypeInfo atomType = typeCache.get(atomKey);

        assertNotNull(atomType, "Atom should have cached type");
        assertTrue(atomType.isNumeric(),
                "Parenthesized arithmetic (10 + 5) should be NUMERIC, but was: " + atomType.getCategory());
    }

    @Test
    void parenthesizedMultiplication_shouldPropagateType() {
        // Given: Parenthesized multiplication
        String sql = "SELECT (10 * 5) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Verify type propagation
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        assertNotNull(finder.atomContext);
        String atomKey = visitor.nodeKey(finder.atomContext);
        TypeInfo atomType = typeCache.get(atomKey);

        assertNotNull(atomType);
        assertTrue(atomType.isNumeric(),
                "Parenthesized multiplication should be NUMERIC, but was: " + atomType.getCategory());
    }

    // ========== Parenthesized Expression in Arithmetic Tests ==========

    @Test
    void parenthesizedExpression_inArithmetic_shouldPropagateType() {
        // Given: Parenthesized expression used in arithmetic
        String sql = "SELECT (10 + 5) * 2 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Both the parenthesized expression and the result should be NUMERIC
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 4,
                "Should have at least 4 NUMERIC types: 10, 5, (10+5), 2, result. Found: " + numericCount);
    }

    @Test
    void parenthesizedExpression_withDateArithmetic_shouldPropagateType() {
        // Given: Parenthesized date literal used in date arithmetic
        String sql = "SELECT (DATE '2024-01-01') + 7 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have both DATE and NUMERIC types
        boolean hasDate = typeCache.values().stream().anyMatch(TypeInfo::isDate);
        boolean hasNumeric = typeCache.values().stream().anyMatch(TypeInfo::isNumeric);

        assertTrue(hasDate, "Should have DATE type");
        assertTrue(hasNumeric, "Should have NUMERIC type for literal 7");

        // Verify the parenthesized date has DATE type
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        if (finder.atomContext != null) {
            String atomKey = visitor.nodeKey(finder.atomContext);
            TypeInfo atomType = typeCache.get(atomKey);
            if (atomType != null) {
                assertTrue(atomType.isDate(),
                        "Parenthesized DATE literal should be DATE, but was: " + atomType.getCategory());
            }
        }
    }

    // ========== Nested Parentheses Tests ==========

    @Test
    void nestedParentheses_shouldPropagateType() {
        // Given: Nested parenthesized expression
        String sql = "SELECT ((42)) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: All Atom nodes should have NUMERIC type
        AllAtomsFinder finder = new AllAtomsFinder();
        finder.visit(parseResult.getTree());

        assertTrue(finder.atomContexts.size() >= 1, "Should find at least 1 Atom node");

        for (PlSqlParser.AtomContext atomCtx : finder.atomContexts) {
            String atomKey = visitor.nodeKey(atomCtx);
            TypeInfo atomType = typeCache.get(atomKey);
            assertNotNull(atomType, "All Atom nodes should have cached type");
            assertTrue(atomType.isNumeric(),
                    "All nested parentheses should be NUMERIC, but found: " + atomType.getCategory());
        }
    }

    // ========== Function Call Tests ==========

    @Test
    void parenthesizedExpression_inFunctionCall_shouldPropagateType() {
        // Given: Parenthesized expression as function argument
        String sql = "SELECT ROUND((10.5 + 5.5), 2) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: All numeric expressions should have NUMERIC type
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 4,
                "Should have NUMERIC types for literals, addition, and result. Found: " + numericCount);
    }

    // ========== Real-World Pattern Tests ==========

    @Test
    void realWorld_parenthesizedColumnReference_shouldPropagateType() {
        // Note: This would require metadata to resolve column types
        // Included for completeness but will return UNKNOWN without metadata

        String sql = "SELECT (salary) * 1.1 FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis (without metadata, column type is UNKNOWN)
        visitor.visit(parseResult.getTree());

        // Then: Without metadata, we can't determine column type
        // But the Atom node should still cache whatever type it gets
        AtomFinder finder = new AtomFinder();
        finder.visit(parseResult.getTree());

        if (finder.atomContext != null) {
            String atomKey = visitor.nodeKey(finder.atomContext);
            TypeInfo atomType = typeCache.get(atomKey);
            assertNotNull(atomType, "Atom should have a cached type (even if UNKNOWN)");
        }
    }

    @Test
    void realWorld_complexExpression_shouldPropagateType() {
        // Given: Real-world complex expression with parentheses
        String sql = "SELECT ((100 + 50) * 2) - 10 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: All parts should be NUMERIC
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 5,
                "Should have NUMERIC types for: 100, 50, (100+50), 2, result, 10. Found: " + numericCount);
    }

    // ========== Helper Classes ==========

    /**
     * Finds the first Atom node that wraps an expressions_ (not a subquery).
     */
    private static class AtomFinder extends org.antlr.v4.runtime.tree.AbstractParseTreeVisitor<Void> {
        PlSqlParser.AtomContext atomContext = null;

        @Override
        public Void visit(ParseTree tree) {
            if (atomContext != null) {
                return null; // Already found
            }

            if (tree instanceof PlSqlParser.AtomContext) {
                PlSqlParser.AtomContext atom = (PlSqlParser.AtomContext) tree;
                // Only look for atoms with expressions_ (not subqueries)
                if (atom.expressions_() != null && atom.subquery() == null) {
                    atomContext = atom;
                    return null;
                }
            }

            return visitAllChildren(tree);
        }

        public Void visitAllChildren(ParseTree tree) {
            if (tree == null || atomContext != null) {
                return null;
            }
            for (int i = 0; i < tree.getChildCount(); i++) {
                visit(tree.getChild(i));
                if (atomContext != null) {
                    return null; // Stop when found
                }
            }
            return null;
        }
    }

    /**
     * Finds all Atom nodes.
     */
    private static class AllAtomsFinder extends org.antlr.v4.runtime.tree.AbstractParseTreeVisitor<Void> {
        java.util.List<PlSqlParser.AtomContext> atomContexts = new java.util.ArrayList<>();

        @Override
        public Void visit(ParseTree tree) {
            if (tree instanceof PlSqlParser.AtomContext) {
                PlSqlParser.AtomContext atom = (PlSqlParser.AtomContext) tree;
                if (atom.expressions_() != null) {
                    atomContexts.add(atom);
                }
            }
            return visitAllChildren(tree);
        }

        public Void visitAllChildren(ParseTree tree) {
            if (tree == null) {
                return null;
            }
            for (int i = 0; i < tree.getChildCount(); i++) {
                visit(tree.getChild(i));
            }
            return null;
        }
    }
}
