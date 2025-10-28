package me.christianrobert.orapgsync.transformation.util;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.TypeAnalysisVisitor;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import me.christianrobert.orapgsync.transformer.util.AstTreeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AstTreeFormatter with type information display.
 *
 * <p>Verifies that type annotations are correctly added to AST output
 * when a type cache is provided.</p>
 */
class AstTreeFormatterWithTypesTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up empty indices (Phase 1 doesn't use metadata)
        indices = new TransformationIndices(
                new HashMap<>(), // tableColumns
                new HashMap<>(), // typeMethods
                new HashSet<>(), // packageFunctions
                new HashMap<>()  // synonyms
        );
    }

    @Test
    void formatWithTypeCache_shouldIncludeTypeAnnotations() {
        // Given: Simple SQL with numeric literal
        String sql = "SELECT 42 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis is performed
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
        visitor.visit(parseResult.getTree());

        // And: Tree is formatted with type cache
        String formattedTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);

        // Then: Output should contain type annotations
        assertNotNull(formattedTree);
        assertTrue(formattedTree.contains("[TYPE: NUMERIC"),
            "Should contain NUMERIC type annotation for literal 42");

        // Verify format includes token positions
        assertTrue(formattedTree.matches("(?s).*\\[TYPE: NUMERIC, \\d+:\\d+\\].*"),
            "Should include token positions in type annotations");

        System.out.println("=== AST Tree with Type Annotations ===");
        System.out.println(formattedTree);
        System.out.println("=====================================");
    }

    @Test
    void formatWithoutTypeCache_shouldNotIncludeTypeAnnotations() {
        // Given: Simple SQL
        String sql = "SELECT 42 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Tree is formatted without type cache
        String formattedTree = AstTreeFormatter.format(parseResult.getTree(), null);

        // Then: Output should NOT contain type annotations
        assertNotNull(formattedTree);
        assertFalse(formattedTree.contains("[TYPE:"),
            "Should not contain type annotations when typeCache is null");
    }

    @Test
    void formatWithArithmeticExpression_shouldShowTypeForExpression() {
        // Given: Arithmetic expression
        String sql = "SELECT 100 + 50 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis is performed
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
        visitor.visit(parseResult.getTree());

        // And: Tree is formatted with type cache
        String formattedTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);

        // Then: Output should contain type annotations for literals AND expression
        assertNotNull(formattedTree);

        // Count occurrences of NUMERIC type annotations
        long numericTypeCount = formattedTree.lines()
            .filter(line -> line.contains("[TYPE: NUMERIC"))
            .count();

        // Should have at least 3 NUMERIC types: 100, 50, and the addition expression
        assertTrue(numericTypeCount >= 3,
            "Should have NUMERIC types for both literals (100, 50) and the addition expression. Found: " + numericTypeCount);

        System.out.println("=== Arithmetic Expression with Type Annotations ===");
        System.out.println(formattedTree);
        System.out.println("=================================================");
    }

    @Test
    void formatWithDateLiteral_shouldShowDateType() {
        // Given: DATE literal
        String sql = "SELECT DATE '2024-01-01' FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis is performed
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
        visitor.visit(parseResult.getTree());

        // And: Tree is formatted with type cache
        String formattedTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);

        // Then: Output should contain DATE type annotation
        assertNotNull(formattedTree);
        assertTrue(formattedTree.contains("[TYPE: DATE"),
            "Should contain DATE type annotation for DATE literal");

        System.out.println("=== DATE Literal with Type Annotation ===");
        System.out.println(formattedTree);
        System.out.println("========================================");
    }

    @Test
    void formatWithStringConcatenation_shouldShowTextType() {
        // Given: String concatenation
        String sql = "SELECT 'Hello' || ' World' FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis is performed
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
        visitor.visit(parseResult.getTree());

        // And: Tree is formatted with type cache
        String formattedTree = AstTreeFormatter.format(parseResult.getTree(), typeCache);

        // Then: Output should contain TEXT type annotations
        assertNotNull(formattedTree);
        assertTrue(formattedTree.contains("[TYPE: TEXT"),
            "Should contain TEXT type annotation for string literals and concatenation");

        System.out.println("=== String Concatenation with Type Annotations ===");
        System.out.println(formattedTree);
        System.out.println("================================================");
    }
}
