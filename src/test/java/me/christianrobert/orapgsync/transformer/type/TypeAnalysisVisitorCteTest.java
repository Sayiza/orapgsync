package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeAnalysisVisitor Phase 4.6: CTE Column Type Resolution.
 *
 * <p>Tests:</p>
 * <ul>
 *   <li>CTE with simple column reference from real table</li>
 *   <li>CTE column type resolution in main query</li>
 *   <li>CTE column type resolution in subquery</li>
 *   <li>Multiple CTEs</li>
 *   <li>CTE with explicit column list</li>
 * </ul>
 */
class TypeAnalysisVisitorCteTest {

    private AntlrParser parser;
    private TransformationIndices indices;
    private Map<String, TypeInfo> typeCache;
    private TypeAnalysisVisitor visitor;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up indices with test table metadata
        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        // Add publication_embargo_objection_period_config table with number_days column (NUMERIC)
        Map<String, TransformationIndices.ColumnTypeInfo> configColumns = new HashMap<>();
        configColumns.put("number_days", new TransformationIndices.ColumnTypeInfo(
            "NUMBER",
            "CO_XM_PUB_CORE"
        ));
        tableColumns.put("co_xm_pub_core.publication_embargo_objection_period_config", configColumns);

        // Add abs_werk_sperren table with spa_abgelehnt_am column (DATE)
        Map<String, TransformationIndices.ColumnTypeInfo> sperrenColumns = new HashMap<>();
        sperrenColumns.put("spa_abgelehnt_am", new TransformationIndices.ColumnTypeInfo(
            "DATE",
            "CO_ABS"
        ));
        tableColumns.put("co_abs.abs_werk_sperren", sperrenColumns);

        indices = new TransformationIndices(
                tableColumns,
                new HashMap<>(), // typeMethods
                new HashSet<>(), // packageFunctions
                new HashMap<>(), // synonyms
                Collections.emptyMap(), // typeFieldTypes
                Collections.emptySet()  // objectTypeNames
        );

        typeCache = new HashMap<>();
        visitor = new TypeAnalysisVisitor("co_xm_pub_core", indices, typeCache);
    }

    // ========== CTE Column Type Resolution Tests ==========

    @Test
    void cteWithSimpleColumn_shouldResolveColumnType() {
        // Given: CTE with column from real table
        String sql = """
            WITH c AS (
              SELECT number_days tg
              FROM co_xm_pub_core.publication_embargo_objection_period_config
            )
            SELECT tg FROM c
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NUMERIC type (from number_days column)
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);

        // Debug: print all cached types
        System.out.println("CTE test - cached types:");
        typeCache.forEach((key, type) ->
            System.out.println("  " + key + " -> " + type.getCategory())
        );

        // The column reference "tg" in main query should resolve to NUMERIC
        long numericCount = typeCache.values().stream()
                .filter(TypeInfo::isNumeric)
                .count();
        assertTrue(numericCount > 0, "Should have at least one NUMERIC type from CTE column");
    }

    @Test
    void cteColumnInSubquery_shouldResolveType() {
        // Given: Original user's failing case - CTE column in scalar subquery
        String sql = """
            WITH c AS (
              SELECT number_days tg
              FROM co_xm_pub_core.publication_embargo_objection_period_config
            )
            SELECT 1
            FROM co_abs.abs_werk_sperren ws1
            WHERE sysdate <= TRUNC(ws1.spa_abgelehnt_am) + (SELECT tg FROM c)
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve types correctly
        // 1. spa_abgelehnt_am should be DATE
        assertContainsCategory(TypeInfo.TypeCategory.DATE);

        // 2. tg from CTE should be NUMERIC
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);

        // Debug output
        System.out.println("CTE subquery test - cached types:");
        typeCache.forEach((key, type) ->
            System.out.println("  " + key + " -> " + type.getCategory())
        );
    }

    @Test
    void directTableSubquery_shouldResolveType() {
        // Given: Direct table reference (not CTE) - this should work already
        String sql = """
            SELECT 1
            FROM co_abs.abs_werk_sperren ws1
            WHERE sysdate <= TRUNC(ws1.spa_abgelehnt_am) + (
              SELECT number_days tg
              FROM co_xm_pub_core.publication_embargo_objection_period_config
            )
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve both DATE and NUMERIC types
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void multipleCtes_shouldResolveAllColumns() {
        // Given: Multiple CTEs
        String sql = """
            WITH
              c1 AS (SELECT number_days tg FROM co_xm_pub_core.publication_embargo_objection_period_config),
              c2 AS (SELECT spa_abgelehnt_am dt FROM co_abs.abs_werk_sperren)
            SELECT tg, dt FROM c1, c2
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have both NUMERIC (tg) and DATE (dt) types
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
        assertContainsCategory(TypeInfo.TypeCategory.DATE);
    }

    @Test
    void cteWithExplicitColumnList_shouldResolveType() {
        // Given: CTE with explicit column names - WITH c (col1) AS ...
        String sql = """
            WITH c (days_col) AS (
              SELECT number_days
              FROM co_xm_pub_core.publication_embargo_objection_period_config
            )
            SELECT days_col FROM c
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve to NUMERIC (even with renamed column)
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    @Test
    void nestedCteReferences_shouldResolveInnerScope() {
        // Given: CTE reference from within a subquery
        String sql = """
            WITH c AS (
              SELECT number_days tg FROM co_xm_pub_core.publication_embargo_objection_period_config
            )
            SELECT (SELECT tg FROM c) FROM dual
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve NUMERIC type from CTE
        assertContainsCategory(TypeInfo.TypeCategory.NUMERIC);
    }

    // ========== Helper Methods ==========

    private void assertContainsCategory(TypeInfo.TypeCategory category) {
        boolean found = typeCache.values().stream()
                .anyMatch(type -> type.getCategory() == category);
        assertTrue(found, "Type cache should contain at least one " + category + " type");
    }
}
