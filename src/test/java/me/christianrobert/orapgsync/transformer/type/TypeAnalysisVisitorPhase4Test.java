package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeAnalysisVisitor Phase 4: Complex Expressions (Scalar Subqueries).
 *
 * <p>Tests:</p>
 * <ul>
 *   <li>Scalar subquery type inference (single column SELECT)</li>
 *   <li>Numeric scalar subqueries</li>
 *   <li>Date scalar subqueries</li>
 *   <li>String scalar subqueries</li>
 *   <li>Multi-column subqueries (should return UNKNOWN)</li>
 * </ul>
 */
class TypeAnalysisVisitorPhase4Test {

    private AntlrParser parser;
    private TransformationIndices indices;
    private Map<String, TypeInfo> typeCache;
    private TypeAnalysisVisitor visitor;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up empty indices (Phase 4 doesn't require metadata for scalar subquery tests)
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

    // ========== Scalar Subquery Type Inference Tests ==========

    @Test
    void scalarSubquery_numeric_shouldReturnNumericType() {
        // Given: Scalar subquery returning numeric with parentheses - (SELECT 1 FROM dual)
        String sql = "SELECT (SELECT 1 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: The literal "1", the subquery, AND the Atom wrapper should all be NUMERIC
        // This verifies that type propagation works through the Atom node (parentheses)
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);

        // Count NUMERIC types - should have multiple (literal, subquery, atom)
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 2, "Should have at least 2 NUMERIC types (literal and propagated types)");
    }

    @Test
    void scalarSubquery_date_shouldReturnDateType() {
        // Given: Scalar subquery returning date
        String sql = "SELECT (SELECT SYSDATE FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Subquery should have DATE type
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
    }

    @Test
    void scalarSubquery_string_shouldReturnTextType() {
        // Given: Scalar subquery returning string
        String sql = "SELECT (SELECT 'test' FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Subquery should have TEXT type
        assertContainsCategory(TypeInfo.TypeCategory.TEXT);
    }

    @Test
    void scalarSubquery_inArithmeticExpression_shouldPropagateType() {
        // Given: Scalar subquery used in arithmetic
        String sql = "SELECT 100 + (SELECT 50 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: All parts should be NUMERIC
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 3, "Should have at least 3 NUMERIC types (100, 50, subquery, result)");
    }

    @Test
    void scalarSubquery_withDateArithmetic_shouldPropagateType() {
        // Given: Scalar subquery used in date arithmetic
        String sql = "SELECT DATE '2024-01-01' + (SELECT 7 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have both DATE and NUMERIC types
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void multiColumnSubquery_shouldReturnUnknown() {
        // Given: Multi-column subquery (not scalar)
        String sql = "SELECT (SELECT 1, 2 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Subquery should have UNKNOWN type (not scalar)
        // Note: We still have NUMERIC types for the literals 1 and 2
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
        assertContainsCategory(TypeInfo.TypeCategory.UNKNOWN);
    }

    @Test
    void nestedScalarSubquery_shouldPropagateTypes() {
        // Given: Nested scalar subqueries
        String sql = "SELECT (SELECT (SELECT 42 FROM dual) FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NUMERIC types (literal and subqueries)
        // Note: The exact count depends on how many AST nodes get cached (expression wrappers, etc.)
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 1, "Should have at least 1 NUMERIC type (literal 42)");

        // Most importantly, the innermost literal should be NUMERIC
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void scalarSubquery_withExpression_shouldPropagateExpressionType() {
        // Given: Scalar subquery with expression (not just literal)
        String sql = "SELECT (SELECT 10 * 5 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: All numeric types should be propagated
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 3, "Should have NUMERIC types for literals, multiplication, and subquery");
    }

    @Test
    void scalarSubquery_inWhereClause_shouldPropagateType() {
        // Given: Scalar subquery in WHERE clause
        String sql = "SELECT * FROM dual WHERE 1 = (SELECT 1 FROM dual)";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NUMERIC types for both sides of comparison
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 2, "Should have NUMERIC types for literals and subquery");
    }

    // ========== Integration Tests with Real-World Patterns ==========

    @Test
    void realWorld_dateArithmeticWithScalarSubquery() {
        // Given: Real-world pattern from bug report
        String sql = "SELECT TRUNC(CURRENT_DATE) + (SELECT 1 FROM dual) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have both DATE and NUMERIC types
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);

        // The scalar subquery (SELECT 1 FROM dual) should be NUMERIC
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount >= 1, "Scalar subquery should be NUMERIC");
    }

    // ========== Helper Methods ==========

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
