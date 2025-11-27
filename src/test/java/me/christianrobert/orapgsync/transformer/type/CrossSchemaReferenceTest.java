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
 * Tests for cross-schema table references in type analysis.
 *
 * <p>Verifies that when a view in schema A references tables in schema B
 * (with explicit schema qualification), column types are correctly resolved.</p>
 */
class CrossSchemaReferenceTest {

    private AntlrParser parser;
    private Map<String, TypeInfo> typeCache;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        typeCache = new HashMap<>();
    }

    @Test
    void schemaQualifiedTable_shouldResolveColumnTypes() {
        // Given: View in schema co_xm_pub_core references table in schema co_abs
        // Simulates the user's bug report scenario

        // Build indices with table from co_abs schema
        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("abs_werk_nr", new TransformationIndices.ColumnTypeInfo("NUMBER", null));
        columns.put("spa_abgelehnt_am", new TransformationIndices.ColumnTypeInfo("DATE", null));

        tableColumns.put("co_abs.abs_werk_sperren", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            Collections.emptyMap(),
            Collections.emptySet()
        );

        // Oracle SQL with explicit schema qualification
        String oracleSql = """
            WITH c AS (
              SELECT 1 tg
              FROM dual
            )
            SELECT distinct ( select 1
                              FROM co_abs.abs_werk_sperren ws1
                              WHERE ws1.abs_werk_nr = ws.abs_werk_nr
                                AND sysdate <= ws1.spa_abgelehnt_am + 34 )  aS endet_am
            FROM co_abs.abs_werk_sperren ws
            """;

        // When: Parse and run type analysis in DIFFERENT schema (co_xm_pub_core)
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        // Run type analysis with schema co_xm_pub_core (NOT co_abs!)
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("co_xm_pub_core", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Then: Should resolve ws1.spa_abgelehnt_am as DATE even though context schema is co_xm_pub_core
        // Because ws1 references co_abs.abs_werk_sperren explicitly

        System.out.println("\n=== Type Cache Contents ===");
        System.out.println(describeCache());
        System.out.println("Total cache entries: " + typeCache.size());

        // Count specific type categories
        long dateCount = typeCache.values().stream().filter(TypeInfo::isDate).count();
        long numericCount = typeCache.values().stream().filter(TypeInfo::isNumeric).count();
        long unknownCount = typeCache.values().stream().filter(TypeInfo::isUnknown).count();

        System.out.println("DATE types: " + dateCount);
        System.out.println("NUMERIC types: " + numericCount);
        System.out.println("UNKNOWN types: " + unknownCount);
        System.out.println("===========================\n");

        // Check that we have DATE types in the cache (from spa_abgelehnt_am)
        boolean hasDateType = typeCache.values().stream()
            .anyMatch(TypeInfo::isDate);

        // THIS ASSERTION SHOULD FAIL if the bug exists!
        // With the current bug, ws1.spa_abgelehnt_am cannot be resolved to DATE
        // because ws1 maps to "abs_werk_sperren" without schema, and lookup uses
        // currentSchema="co_xm_pub_core", building key "co_xm_pub_core.abs_werk_sperren"
        // which doesn't exist (should be "co_abs.abs_werk_sperren")
        assertTrue(hasDateType,
            "Expected to find DATE type for spa_abgelehnt_am column even when transforming in different schema. " +
            "Cache contents: " + describeCache());

        // Check that we have NUMERIC types (from 34 and the numeric column)
        boolean hasNumericType = typeCache.values().stream()
            .anyMatch(TypeInfo::isNumeric);

        assertTrue(hasNumericType,
            "Expected to find NUMERIC type. Cache contents: " + describeCache());
    }

    @Test
    void unqualifiedTable_shouldUseCurrentSchema() {
        // Given: Unqualified table reference should use current schema

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hire_date", new TransformationIndices.ColumnTypeInfo("DATE", null));

        // Table in current schema (hr)
        tableColumns.put("hr.employees", columns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            Collections.emptyMap(),
            Collections.emptySet()
        );

        // Oracle SQL WITHOUT explicit schema qualification
        String oracleSql = "SELECT hire_date + 30 FROM employees";

        // When: Parse and run type analysis
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("hr", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Then: Should resolve hire_date using current schema (hr)
        boolean hasDateType = typeCache.values().stream()
            .anyMatch(TypeInfo::isDate);

        assertTrue(hasDateType,
            "Expected to find DATE type for hire_date. Cache contents: " + describeCache());
    }

    @Test
    void mixedQualifiedAndUnqualified_shouldResolveCorrectly() {
        // Given: Mix of qualified and unqualified table references

        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        // Table in current schema
        Map<String, TransformationIndices.ColumnTypeInfo> empColumns = new HashMap<>();
        empColumns.put("emp_id", new TransformationIndices.ColumnTypeInfo("NUMBER", null));
        empColumns.put("hire_date", new TransformationIndices.ColumnTypeInfo("DATE", null));
        tableColumns.put("hr.employees", empColumns);

        // Table in different schema
        Map<String, TransformationIndices.ColumnTypeInfo> deptColumns = new HashMap<>();
        deptColumns.put("dept_id", new TransformationIndices.ColumnTypeInfo("NUMBER", null));
        deptColumns.put("created_date", new TransformationIndices.ColumnTypeInfo("DATE", null));
        tableColumns.put("finance.departments", deptColumns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            Collections.emptyMap(),
            Collections.emptySet()
        );

        // Oracle SQL with both qualified and unqualified references
        String oracleSql = """
            SELECT
                e.hire_date + 30,
                d.created_date + 60
            FROM employees e
            JOIN finance.departments d ON e.emp_id = d.dept_id
            """;

        // When: Parse and run type analysis in hr schema
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("hr", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Then: Should resolve both columns correctly
        long dateCount = typeCache.values().stream()
            .filter(TypeInfo::isDate)
            .count();

        assertTrue(dateCount >= 2,
            "Expected to find at least 2 DATE types (hire_date and created_date). Cache contents: " + describeCache());
    }

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
