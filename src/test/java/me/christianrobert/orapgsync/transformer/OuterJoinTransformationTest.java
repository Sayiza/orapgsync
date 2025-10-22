package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle (+) outer join transformation to ANSI SQL JOIN syntax.
 *
 * <p>Test cases cover:
 * <ul>
 *   <li>Single outer join (LEFT/RIGHT)</li>
 *   <li>Multiple outer joins (chained)</li>
 *   <li>Multi-column joins</li>
 *   <li>Mixed outer joins and regular WHERE conditions</li>
 *   <li>Table aliases</li>
 * </ul>
 */
class OuterJoinTransformationTest {

    @Test
    void testSimpleLeftOuterJoin() {
        // Oracle: FROM a, b WHERE a.field1 = b.field1(+)
        // Expected: FROM a LEFT JOIN b ON a.field1 = b.field1
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1 = b.field1(+)
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN"), "Should contain LEFT JOIN");
        assertTrue(result.contains("ON a . field1 = b . field1"), "Should have ON clause without (+)");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
        assertFalse(result.contains("WHERE"), "Should not have WHERE clause (all conditions moved to JOIN)");
    }

    @Test
    void testSimpleRightOuterJoin() {
        // Oracle: FROM a, b WHERE a.field1(+) = b.field1
        // Expected: FROM a RIGHT JOIN b ON a.field1 = b.field1
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1(+) = b.field1
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("RIGHT JOIN"), "Should contain RIGHT JOIN");
        assertTrue(result.contains("ON a . field1 = b . field1"), "Should have ON clause without (+)");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testChainedOuterJoins() {
        // Oracle: FROM a, b, c WHERE a.field1 = b.field1(+) AND b.field2 = c.field2(+)
        // Expected: FROM a LEFT JOIN b ON a.field1 = b.field1 LEFT JOIN c ON b.field2 = c.field2
        String oracleSql = """
            SELECT a.col1, b.col2, c.col3
            FROM a, b, c
            WHERE a.field1 = b.field1(+)
              AND b.field2 = c.field2(+)
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN b"), "Should contain LEFT JOIN b");
        assertTrue(result.contains("LEFT JOIN c"), "Should contain LEFT JOIN c");
        assertTrue(result.contains("ON a . field1 = b . field1"), "Should have first ON clause");
        assertTrue(result.contains("ON b . field2 = c . field2"), "Should have second ON clause");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testMultiColumnJoin() {
        // Oracle: FROM a, b WHERE a.field1 = b.field1(+) AND a.field2 = b.field2(+)
        // Expected: FROM a LEFT JOIN b ON a.field1 = b.field1 AND a.field2 = b.field2
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1 = b.field1(+)
              AND a.field2 = b.field2(+)
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN"), "Should contain LEFT JOIN");
        assertTrue(result.contains("ON a . field1 = b . field1 AND a . field2 = b . field2"),
            "Should combine multiple conditions with AND");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testOuterJoinWithRegularWhereCondition() {
        // Oracle: FROM a, b WHERE a.field1 = b.field1(+) AND a.col1 > 10
        // Expected: FROM a LEFT JOIN b ON a.field1 = b.field1 WHERE a.col1 > 10
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1 = b.field1(+)
              AND a.col1 > 10
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN"), "Should contain LEFT JOIN");
        assertTrue(result.contains("ON a . field1 = b . field1"), "Should have ON clause");
        assertTrue(result.contains("WHERE a . col1 > 10"), "Should preserve regular WHERE condition");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testOuterJoinWithAliases() {
        // Oracle: FROM employees e, departments d WHERE e.dept_id = d.id(+)
        // Expected: FROM employees e LEFT JOIN departments d ON e.dept_id = d.id
        String oracleSql = """
            SELECT e.name, d.dept_name
            FROM employees e, departments d
            WHERE e.dept_id = d.id(+)
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("employees e"), "Should preserve table alias e");
        assertTrue(result.contains("LEFT JOIN departments d"), "Should preserve alias d in JOIN");
        assertTrue(result.contains("ON e . dept_id = d . id"), "Should use aliases in ON clause");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testComplexScenario() {
        // Oracle: Three tables, two outer joins, one regular condition
        // FROM a, b, c WHERE a.f1 = b.f1(+) AND b.f2 = c.f2(+) AND a.col1 > 10
        // Expected: FROM a LEFT JOIN b ON a.f1 = b.f1 LEFT JOIN c ON b.f2 = c.f2 WHERE a.col1 > 10
        String oracleSql = """
            SELECT a.col1, b.col2, c.col3
            FROM a, b, c
            WHERE a.f1 = b.f1(+)
              AND b.f2 = c.f2(+)
              AND a.col1 > 10
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN b"), "Should contain LEFT JOIN b");
        assertTrue(result.contains("LEFT JOIN c"), "Should contain LEFT JOIN c");
        assertTrue(result.contains("ON a . f1 = b . f1"), "Should have first ON clause");
        assertTrue(result.contains("ON b . f2 = c . f2"), "Should have second ON clause");
        assertTrue(result.contains("WHERE a . col1 > 10"), "Should preserve regular WHERE condition");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testImplicitJoinWithoutOuterJoin() {
        // Query with multiple tables but no outer join - should leave as implicit join
        // PostgreSQL supports implicit joins (comma-separated tables) perfectly fine
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1 = b.field1
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        // Should preserve implicit join syntax (comma-separated)
        assertTrue(result.contains("FROM a,b") || result.contains("FROM a, b"), "Should preserve implicit join");
        assertTrue(result.contains("a.field1=b.field1") || result.contains("a . field1 = b . field1"),
            "Should preserve WHERE condition");
        assertFalse(result.contains("JOIN"), "Should not have explicit JOIN (no outer join to transform)");
        assertFalse(result.contains("(+)"), "Should not contain (+)");
    }

    @Test
    void testMultiTableImplicitJoin() {
        // Three tables with implicit joins (no outer joins)
        String oracleSql = """
            SELECT a.col1, b.col2, c.col3
            FROM a, b, c
            WHERE a.field1 = b.field1
              AND b.field2 = c.field2
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        // Check for comma-separated tables (may or may not have spaces)
        assertTrue(result.contains("FROM a,b,c") || result.contains("FROM a, b, c") || result.contains("FROM a , b , c"),
            "Should preserve all implicit joins");
        // Check that key identifiers are present in WHERE clause
        assertTrue(result.contains("a") && result.contains("b") && result.contains("c"),
            "Should preserve table references");
        assertTrue(result.contains("field1") && result.contains("field2"),
            "Should preserve field references");
        assertFalse(result.contains("JOIN"), "Should not have explicit JOIN");
    }

    @Test
    void testMixedOuterAndImplicitJoins() {
        // Mix of outer join (a-b) and implicit join (with c)
        // This is valid PostgreSQL: you can mix ANSI JOINs with comma-separated tables
        String oracleSql = """
            SELECT a.col1, b.col2, c.col3
            FROM a, b, c
            WHERE a.field1 = b.field1(+)
              AND a.field2 = c.field2
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        // Should have ANSI JOIN for the outer join
        assertTrue(result.contains("LEFT JOIN b"), "Should have LEFT JOIN for outer join");
        assertTrue(result.contains("ON a . field1 = b . field1"), "Should have ON clause for outer join");
        // Should keep c as implicit join
        assertTrue(result.contains(", c"), "Should have comma-separated c for implicit join");
        // WHERE should only have the implicit join condition
        assertTrue(result.contains("WHERE a . field2 = c . field2"), "Should preserve implicit join condition in WHERE");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testMixedMultipleOuterAndImplicitJoins() {
        // Two outer joins (a-b, b-c) and one implicit join (with d)
        String oracleSql = """
            SELECT a.col1, b.col2, c.col3, d.col4
            FROM a, b, c, d
            WHERE a.f1 = b.f1(+)
              AND b.f2 = c.f2(+)
              AND a.f3 = d.f3
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        // Should have two ANSI JOINs
        assertTrue(result.contains("LEFT JOIN b"), "Should have LEFT JOIN for first outer join");
        assertTrue(result.contains("LEFT JOIN c"), "Should have LEFT JOIN for second outer join");
        // Should keep d as implicit join
        assertTrue(result.contains(", d"), "Should have comma-separated d for implicit join");
        // WHERE should only have the implicit join condition
        assertTrue(result.contains("WHERE a . f3 = d . f3"), "Should preserve implicit join condition in WHERE");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testNonEqualityOuterJoinShouldThrowException() {
        // Oracle: a.field1 > b.field1(+) - non-equality outer join
        // This is not supported in ANSI SQL and should throw exception
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1 > b.field1(+)
            """;

        assertThrows(Exception.class, () -> transformQuery(oracleSql),
            "Should throw exception for non-equality outer join");
    }

    @Test
    void testBothSidesWithPlusShouldThrowException() {
        // Oracle: a.field1(+) = b.field1(+) - invalid syntax
        // Should throw exception
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1(+) = b.field1(+)
            """;

        assertThrows(Exception.class, () -> transformQuery(oracleSql),
            "Should throw exception when both sides have (+)");
    }

    // ========== NESTED SUBQUERY TESTS ==========
    //
    // Note: Subqueries in SELECT clause are not yet supported by the transformer
    // (separate limitation from outer join handling).
    // These tests focus on subqueries in WHERE clause (IN, EXISTS, etc.) which ARE supported.

    @Test
    void testSubqueryInWhereWithOuterJoin() {
        // Outer query has outer join, subquery in WHERE clause
        String oracleSql = """
            SELECT a.col1
            FROM a, d
            WHERE a.id = d.id(+)
              AND a.col1 IN (SELECT b.col1 FROM b)
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN d"), "Should have LEFT JOIN");
        assertTrue(result.contains("ON a . id = d . id"), "Should have ON clause");
        assertTrue(result.contains("WHERE a . col1 IN"), "Should preserve IN subquery in WHERE");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    @Test
    void testSubqueryInWhereWithBothHavingOuterJoins() {
        // Both outer query and subquery in WHERE have outer joins
        // NOTE: Due to current limitation (getText() capturing raw text), the subquery's
        // (+) will not be transformed. This test verifies the outer query works correctly.
        String oracleSql = """
            SELECT a.col1
            FROM a, d
            WHERE a.id = d.id(+)
              AND a.col1 IN (SELECT b.col1 FROM b, c WHERE b.id = c.id(+))
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        // Outer query transformation should work
        assertTrue(result.contains("FROM a LEFT JOIN d"), "Outer query should have LEFT JOIN");
        assertTrue(result.contains("ON a . id = d . id"), "Should have ON clause for outer query");
        assertTrue(result.contains("WHERE a . col1 IN"), "Should preserve IN subquery in WHERE");

        // Known limitation: The subquery's (+) will not be transformed in this case
        // because the entire IN expression is captured as raw text during analysis
    }

    @Test
    void testNestedSubqueriesInWhereWithOuterJoins() {
        // Two levels: outer query and subquery in WHERE - both with outer joins
        // This tests that the context stack properly isolates each query level
        String oracleSql = """
            SELECT a.col1
            FROM a, d
            WHERE a.id = d.id(+)
              AND a.status IN (
                SELECT e.status
                FROM e, f
                WHERE e.type = f.type(+)
                  AND f.active = 1
              )
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        // Verify outer query transformation
        assertTrue(result.contains("FROM a LEFT JOIN d"), "Outer query should have LEFT JOIN");
        assertTrue(result.contains("ON a . id = d . id"), "Should have ON clause for outer query");

        // Note: The subquery's (+) will NOT be transformed because we're capturing
        // the raw text during analysis. This is a known limitation of the current
        // approach where we use getText() to store regular WHERE conditions.
        // The proper fix would be to store AST nodes instead of strings.
        // For now, we verify the outer query transformation works correctly
        // with nested queries, even if the inner query's (+) isn't transformed.
    }

    @Test
    void testCorrelatedSubqueryWithOuterJoin() {
        // Correlated subquery (references outer table) with outer join in outer query
        String oracleSql = """
            SELECT a.col1
            FROM a, d
            WHERE a.id = d.id(+)
              AND EXISTS (SELECT 1 FROM b WHERE b.a_id = a.id)
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("LEFT JOIN d"), "Should have LEFT JOIN");
        assertTrue(result.contains("ON a . id = d . id"), "Should have ON clause");
        assertTrue(result.contains("EXISTS"), "Should preserve EXISTS subquery");
        assertTrue(result.contains("b . a_id = a . id"), "Should preserve correlated reference");
        assertFalse(result.contains("(+)"), "Should not contain (+) in result");
    }

    /**
     * Helper method to count occurrences of a substring.
     */
    private int countOccurrences(String str, String substring) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    /**
     * Helper method to transform Oracle SQL to PostgreSQL SQL.
     */
    private String transformQuery(String oracleSql) {
        // Parse Oracle SQL
        AntlrParser parser = new AntlrParser();
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);

        // Check for parse errors
        if (!parseResult.getErrors().isEmpty()) {
            throw new RuntimeException("Parse errors: " + parseResult.getErrors());
        }

        // Transform to PostgreSQL (without metadata context for simple tests)
        PostgresCodeBuilder builder = new PostgresCodeBuilder();
        String result = builder.visit(parseResult.getTree());

        // Remove extra whitespace for easier assertion
        result = result.replaceAll("\\s+", " ").trim();

        System.out.println("Original: " + oracleSql.replaceAll("\\s+", " ").trim());
        System.out.println("Result:   " + result);
        System.out.println();

        return result;
    }
}
