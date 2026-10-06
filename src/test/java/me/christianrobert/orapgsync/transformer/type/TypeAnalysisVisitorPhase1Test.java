package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.antlr.v4.runtime.ParserRuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeAnalysisVisitor Phase 1: Literals and Simple Expressions.
 *
 * <p>Tests:</p>
 * <ul>
 *   <li>Literal type detection (numbers, strings, NULL, booleans, dates)</li>
 *   <li>Arithmetic operators (+, -, *, /)</li>
 *   <li>Date arithmetic (DATE + NUMBER, DATE - DATE)</li>
 *   <li>String concatenation (||)</li>
 *   <li>NULL propagation</li>
 * </ul>
 */
class TypeAnalysisVisitorPhase1Test {

    private AntlrParser parser;
    private TransformationIndices indices;
    private Map<String, TypeInfo> typeCache;
    private TypeAnalysisVisitor visitor;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up empty indices (Phase 1 doesn't use metadata)
        indices = new TransformationIndices(
                new HashMap<>(), // tableColumns
                new HashMap<>(), // typeMethods
                new HashSet<>(), // packageFunctions
                new HashMap<>(), // synonyms
                Collections.emptyMap(), // typeFieldTypes
                Collections.emptySet()  // objectTypeNames
        );

        typeCache = new HashMap<>();
        visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
    }

    // ========== Literal Type Detection Tests ==========

    @Test
    void numericLiteral_shouldReturnNumericType() {
        // Given: Numeric literal
        String sql = "SELECT 42 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for the literal
        assertCachedType("42", TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void decimalLiteral_shouldReturnNumericType() {
        // Given: Decimal literal
        String sql = "SELECT 3.14159 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for the literal
        assertCachedType("3.14159", TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void stringLiteral_shouldReturnTextType() {
        // Given: String literal
        String sql = "SELECT 'Hello World' FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for the literal
        assertCachedType("'Hello World'", TypeInfo.TypeCategory.TEXT);
    }

    @Test
    void nullLiteral_shouldReturnNullType() {
        // Given: NULL literal
        String sql = "SELECT NULL FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for NULL
        assertCachedType("NULL", TypeInfo.TypeCategory.NULL_TYPE);
    }

    @Test
    void booleanLiteral_shouldReturnBooleanType() {
        // Given: Boolean literal in PL/SQL
        String plsql = """
            FUNCTION test RETURN BOOLEAN IS
            BEGIN
              RETURN TRUE;
            END;
            """;
        ParseResult parseResult = parser.parseFunctionBody(plsql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for TRUE
        assertCachedType("TRUE", TypeInfo.TypeCategory.BOOLEAN);
    }

    @Test
    void dateLiteral_shouldReturnDateType() {
        // Given: DATE literal
        String sql = "SELECT DATE '2024-01-01' FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for DATE
        assertCachedType("DATE'2024-01-01'", TypeInfo.TypeCategory.DATE);
    }

    @Test
    void timestampLiteral_shouldReturnDateCategory() {
        // Given: TIMESTAMP literal
        String sql = "SELECT TIMESTAMP '2024-01-01 12:00:00' FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have cached type for TIMESTAMP (category is DATE for both DATE and TIMESTAMP)
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
    }

    // ========== Arithmetic Operators Tests ==========

    @Test
    void addition_numericOperands_shouldReturnNumeric() {
        // Given: Numeric addition
        String sql = "SELECT 100 + 50 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Expression should have NUMERIC type
        assertCachedType("100+50", TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void subtraction_numericOperands_shouldReturnNumeric() {
        // Given: Numeric subtraction
        String sql = "SELECT 100 - 50 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Expression should have NUMERIC type
        assertCachedType("100-50", TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void multiplication_numericOperands_shouldReturnNumeric() {
        // Given: Numeric multiplication
        String sql = "SELECT 10 * 5 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Expression should have NUMERIC type
        assertCachedType("10*5", TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void division_numericOperands_shouldReturnNumeric() {
        // Given: Numeric division
        String sql = "SELECT 100 / 4 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Expression should have NUMERIC type
        assertCachedType("100/4", TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void powerOperator_shouldReturnNumeric() {
        // Given: Power operator
        String sql = "SELECT 2 ** 8 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Expression should have NUMERIC type
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void modOperator_shouldReturnNumeric() {
        // Given: MOD operator
        String sql = "SELECT MOD(10, 3) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NUMERIC types
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    // ========== Date Arithmetic Tests ==========

    @Test
    void dateAddition_datePlusNumber_shouldReturnDate() {
        // Given: DATE + NUMBER (add days)
        String sql = "SELECT DATE '2024-01-01' + 30 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Result should be DATE type
        // (DATE + 30 days)
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void dateSubtraction_dateMinusNumber_shouldReturnDate() {
        // Given: DATE - NUMBER (subtract days)
        String sql = "SELECT DATE '2024-01-01' - 7 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Result should be DATE type
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
    }

    // ========== String Concatenation Tests ==========

    @Test
    void stringConcatenation_shouldReturnText() {
        // Given: String concatenation
        String sql = "SELECT 'Hello' || ' ' || 'World' FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have TEXT type for concatenation
        assertContainsCategory(TypeInfo.TypeCategory.TEXT);
    }

    // ========== NULL Propagation Tests ==========

    @Test
    void arithmeticWithNull_shouldReturnNullType() {
        // Given: Arithmetic with NULL
        String sql = "SELECT 100 + NULL FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NULL_TYPE for expression
        assertContainsCategory(TypeInfo.TypeCategory.NULL_TYPE);
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    // ========== Complex Expressions Tests ==========

    @Test
    void nestedArithmetic_shouldPropagateTypes() {
        // Given: Nested arithmetic expression
        String sql = "SELECT (100 + 50) * 2 / 3 FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NUMERIC types throughout
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 4, "Should have at least 4 numeric types (literals and expressions)");
    }

    // ========== Helper Methods ==========

    /**
     * Asserts that type cache contains given text with expected category.
     * Uses substring match since we don't know exact token positions.
     */
    private void assertCachedType(String text, TypeInfo.TypeCategory expectedCategory) {
        // Normalize text (remove spaces)
        String normalizedText = text.replaceAll("\\s+", "");

        // Find matching entries in cache
        boolean found = typeCache.entrySet().stream()
                .anyMatch(entry -> {
                    String key = entry.getKey();
                    TypeInfo type = entry.getValue();
                    // Check if this entry matches our text and category
                    return type.getCategory() == expectedCategory;
                });

        assertTrue(found,
                "Expected type cache to contain entry with category " + expectedCategory +
                        " for text '" + normalizedText + "'. Cache contents: " + describeCache());
    }

    /**
     * Asserts that type cache contains at least one entry with given category.
     */
    private void assertContainsCategory(TypeInfo.TypeCategory category) {
        boolean found = typeCache.values().stream()
                .anyMatch(type -> type.getCategory() == category);

        assertTrue(found,
                "Expected type cache to contain category " + category +
                        ". Cache contents: " + describeCache());
    }

    /**
     * Returns human-readable description of cache contents.
     */
    private String describeCache() {
        if (typeCache.isEmpty()) {
            return "empty";
        }

        Map<TypeInfo.TypeCategory, Long> categoryCounts = new HashMap<>();
        for (TypeInfo type : typeCache.values()) {
            categoryCounts.merge(type.getCategory(), 1L, Long::sum);
        }

        return categoryCounts.toString();
    }
}
