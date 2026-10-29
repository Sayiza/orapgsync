package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
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
        // Oracle: FROM a, b WHERE a.field1(+) = b.field1 — a is the optional side
        // Expected: FROM b LEFT JOIN a ON a.field1 = b.field1 (the optional side is always the one joined)
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1(+) = b.field1
            """;

        String result = transformQuery(oracleSql);

        assertNotNull(result);
        assertTrue(result.contains("FROM b LEFT JOIN a ON a . field1 = b . field1"), result);
        assertFalse(result.contains("RIGHT JOIN"), result);
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
    void testNonEqualityOuterJoin() {
        // Oracle: a.field1 > b.field1(+) — valid Oracle, and an ON clause takes any condition
        String oracleSql = """
            SELECT a.col1, b.col2
            FROM a, b
            WHERE a.field1 > b.field1(+)
            """;

        String result = transformQuery(oracleSql);

        assertTrue(result.contains("FROM a LEFT JOIN b ON a . field1 > b . field1"), result);
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
        // Both outer query and subquery in WHERE have outer joins; each query block is analyzed on its own
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
        assertTrue(result.contains("FROM b LEFT JOIN c ON b . id = c . id"), "Subquery's (+) should be transformed");
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

    // ========== SHAPES THAT WERE SILENTLY LOST (ROADMAP P1.14) ==========
    //
    // Each of these used to compile into valid PostgreSQL with different rows: the (+) was
    // stripped, the condition dropped or moved to WHERE, or the join direction inverted.

    @Test
    void parenthesizedConditionsAreOuterJoins() {
        String result = transformQuery("""
            SELECT a.x
            FROM t_a a, t_c c, t_d d, t_h h, t_i i, t_n n
            WHERE 1 = 1
              and ( a.n_nr = n.nr(+) )
              AND ( a.c_nr = c.nr )
              AND ( a.d_nr = d.nr )
              AND ( d.h_nr = h.nr (+) )
              AND ( a.i_nr = i.nr (+) )
              and c.code in ('A', 'B')
            """);

        assertEquals("SELECT a . x FROM t_a a CROSS JOIN t_c c CROSS JOIN t_d d"
            + " LEFT JOIN t_h h ON d . h_nr = h . nr"
            + " LEFT JOIN t_i i ON a . i_nr = i . nr"
            + " LEFT JOIN t_n n ON a . n_nr = n . nr"
            + " WHERE 1 = 1 AND a . c_nr = c . nr AND a . d_nr = d . nr AND c . code IN ( 'A', 'B' )", result);
    }

    @Test
    void parenthesizedAndGroupIsSplit() {
        String result = transformQuery("SELECT a.x FROM a, b WHERE (a.k = b.k(+) AND a.j = b.j(+))");

        assertEquals("SELECT a . x FROM a LEFT JOIN b ON a . k = b . k AND a . j = b . j", result);
    }

    @Test
    void parenthesizedOrGroupKeepsItsParentheses() {
        String result = transformQuery("SELECT a.x FROM a, b WHERE a.k = b.k(+) AND (a.p = 1 OR a.q = 2)");

        assertEquals("SELECT a . x FROM a LEFT JOIN b ON a . k = b . k WHERE (a . p = 1 OR a . q = 2)", result);
    }

    @Test
    void outerJoinedTableBeforeItsPartnerIsMovedAfterIt() {
        // a(+) is listed first; it must follow b, and is joined with LEFT JOIN, never RIGHT JOIN
        String result = transformQuery("SELECT a.x, b.y FROM a, b WHERE a.k(+) = b.k");

        assertEquals("SELECT a . x , b . y FROM b LEFT JOIN a ON a . k = b . k", result);
    }

    @Test
    void optionalSideOnTheLeftOfAnAlreadyJoinedTable() {
        // c.j(+) = b.j makes c optional; it used to become RIGHT JOIN c, preserving c instead
        String result = transformQuery("SELECT 1 FROM a, b, c WHERE a.k = b.k(+) AND c.j(+) = b.j");

        assertEquals("SELECT 1 FROM a LEFT JOIN b ON a . k = b . k LEFT JOIN c ON c . j = b . j", result);
    }

    @Test
    void preservedTableNotFirstInFrom() {
        // The LEFT JOIN used to be attached to x, referencing a before a was in scope
        String result = transformQuery("SELECT 1 FROM x, s, a WHERE x.t = a.nr AND a.s_id = s.id(+)");

        // x needs no ON, so it may stay comma-separated; a and s must form one join tree
        assertEquals("SELECT 1 FROM x, a LEFT JOIN s ON a . s_id = s . id WHERE x . t = a . nr", result);
    }

    @Test
    void conditionsAgainstTwoTablesShareOneOnClause() {
        // The second condition on c used to be dropped
        String result = transformQuery("SELECT 1 FROM a, b, c WHERE a.k = b.k AND a.j = c.j(+) AND b.m = c.m(+)");

        assertEquals("SELECT 1 FROM a CROSS JOIN b LEFT JOIN c ON a . j = c . j AND b . m = c . m WHERE a . k = b . k",
            result);
    }

    @Test
    void constantConditionOnOptionalSideBelongsToTheOnClause() {
        // In WHERE it would discard the rows the outer join keeps
        String result = transformQuery("SELECT a.x FROM a, b WHERE a.k = b.k(+) AND b.s(+) = 'X'");

        assertEquals("SELECT a . x FROM a LEFT JOIN b ON a . k = b . k AND b . s = 'X'", result);
    }

    @Test
    void constantConditionWithoutJoinIsAPlainFilter() {
        // Oracle: no predicate links b to another table, so b is not outer-joined at all
        String result = transformQuery("SELECT a.x FROM a, b WHERE b.s(+) = 'X'");

        assertEquals("SELECT a . x FROM a, b WHERE b . s = 'X'", result);
    }

    @Test
    void outerJoinToCorrelatedOuterColumnIsAPlainFilter() {
        // Verified against Oracle: the subquery's own FROM has nothing to join k to
        String result = transformQuery(
            "SELECT o.id, (SELECT k.label FROM k WHERE o.kind_nr = k.nr(+)) AS label FROM o");

        assertTrue(result.contains("(SELECT k . label FROM k WHERE o . kind_nr = k . nr)"), result);
    }

    @Test
    void outerJoinInsideFunctionCall() {
        String result = transformQuery("SELECT a.x FROM a, b WHERE nvl(b.k(+), 0) = a.k");

        assertTrue(result.startsWith("SELECT a . x FROM a LEFT JOIN b ON "), result);
        assertTrue(result.contains("b . k"), result);
        assertFalse(result.contains("WHERE"), result);
    }

    @Test
    void preservedSideInsideToCharIsStillADependency() {
        // TO_CHAR's first argument parses as table_element, not general_element; missing it made
        // the condition look like a filter on y alone, i.e. an inner join
        String result = transformQuery(
            "SELECT 1 FROM x, y WHERE to_char(x.d, 'DD.MM.YYYY') = to_char(y.d(+), 'DD.MM.YYYY')");

        assertTrue(result.startsWith("SELECT 1 FROM x LEFT JOIN y ON "), result);
        assertFalse(result.contains("WHERE"), result);
    }

    @Test
    void outerJoinInBetween() {
        String result = transformQuery("SELECT a.x FROM a, b WHERE a.d BETWEEN b.lo(+) AND b.hi(+)");

        assertEquals("SELECT a . x FROM a LEFT JOIN b ON a . d BETWEEN b . lo AND b . hi", result);
    }

    @Test
    void schemaQualifiedColumn() {
        String result = transformQuery("SELECT 1 FROM hr.a, hr.b WHERE hr.a.k = hr.b.k(+)");

        assertEquals("SELECT 1 FROM hr.a LEFT JOIN hr.b ON hr.a . k = hr.b . k", result.replace("hr . ", "hr."));
    }

    @Test
    void quotedAliasMatchesUnquotedReference() {
        String result = transformQuery("SELECT 1 FROM a \"X\", b WHERE x.k = b.k(+)");

        assertTrue(result.contains("LEFT JOIN b ON"), result);
    }

    @Test
    void unrelatedTrailingTablesStayCommaSeparated() {
        String result = transformQuery("SELECT 1 FROM a, b, c, d WHERE a.k = b.k(+) AND a.c = c.nr AND a.d = d.nr");

        assertEquals("SELECT 1 FROM a LEFT JOIN b ON a . k = b . k, c, d WHERE a . c = c . nr AND a . d = d . nr",
            result);
    }

    @Test
    void inlineViewWithConnectByUsesItsOwnContext() {
        // The inline view is transformed outside VisitQueryBlock's analysis; it used to pick up the
        // enclosing query's context and fail on a table count mismatch — or, had the counts
        // matched, build its FROM from the enclosing query's join conditions
        String result = transformQuery("""
            SELECT a.nr, s.nr
            FROM t_a a,
                 (SELECT k.nr FROM t_k k START WITH k.parent_id IS NULL CONNECT BY PRIOR k.nr = k.parent_id) s
            WHERE a.k_nr = s.nr(+)
            """);

        assertTrue(result.contains("FROM t_a a LEFT JOIN ("), result);
        assertTrue(result.endsWith("s ON a . k_nr = s . nr"), result);
    }

    // ========== REFUSED BY NAME ==========

    @Test
    void outerJoinInOrIsRefused() {
        assertRefused("SELECT 1 FROM a, b WHERE a.k = b.k(+) OR a.z = 1", "ORA-01719");
    }

    @Test
    void outerJoinAgainstSubqueryIsRefused() {
        assertRefused("SELECT 1 FROM a, b WHERE a.k = b.k(+) AND b.x(+) = (SELECT max(y) FROM c)", "ORA-01799");
    }

    @Test
    void tablesOuterJoinedToEachOtherAreRefused() {
        assertRefused("SELECT 1 FROM a, b WHERE a.k = b.k(+) AND b.j = a.j(+)", "ORA-01416");
    }

    @Test
    void outerJoinMixedWithAnsiJoinIsRefused() {
        assertRefused("SELECT 1 FROM a x JOIN b y ON x.k = y.k, c WHERE x.j = c.j(+)", "ORA-25156");
    }

    @Test
    void unqualifiedOuterJoinColumnIsRefused() {
        assertRefused("SELECT 1 FROM a, b WHERE a.k = k2(+)", "unqualified");
    }

    @Test
    void qualifierOutsideFromIsRefused() {
        assertRefused("SELECT 1 FROM a, b WHERE a.k = z.k(+)", "names no item");
    }

    @Test
    void selectStarOverReorderedFromIsRefused() {
        assertRefused("SELECT * FROM a, b WHERE a.k(+) = b.k", "SELECT *");
    }

    @Test
    void selectStarKeepingFromOrderIsAllowed() {
        assertEquals("SELECT * FROM a LEFT JOIN b ON a . k = b . k", transformQuery("SELECT * FROM a, b WHERE a.k = b.k(+)"));
    }

    @Test
    void outerJoinOutsideWhereIsRefused() {
        // Stripping it would leave a plain column; nothing translates (+) in a SELECT list
        assertRefused("SELECT b.k(+) FROM a, b WHERE a.k = b.k", "not translated");
    }

    @Test
    void outerJoinInInnerJoinOnClauseIsRefused() {
        // Oracle makes this an outer join; stripping the marker would leave an inner one
        assertRefused("SELECT 1 FROM a x JOIN b y ON x.k = y.k(+)", "not translated");
    }

    @Test
    void outerJoinOnJoinedTableInLeftJoinIsRedundant() {
        // The joined table is optional already; Oracle gives the marker no effect
        String result = transformQuery("SELECT 1 FROM a x LEFT JOIN b y ON x.k = y.k(+) AND x.j = y.j");

        assertEquals("SELECT 1 FROM a x LEFT JOIN b y ON x . k = y . k AND x . j = y . j", result);
    }

    @Test
    void outerJoinOnPreservedTableInLeftJoinIsRefused() {
        assertRefused("SELECT 1 FROM a x LEFT JOIN b y ON x.k(+) = y.k", "not translated");
    }

    private void assertRefused(String oracleSql, String messageFragment) {
        TransformationException e = assertThrows(TransformationException.class, () -> transformQuery(oracleSql));
        assertTrue(e.getMessage().contains(messageFragment), e.getMessage());
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
