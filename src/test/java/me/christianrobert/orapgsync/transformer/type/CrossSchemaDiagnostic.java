package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Diagnostic to investigate cross-schema reference issue.
 */
public class CrossSchemaDiagnostic {

    public static void main(String[] args) {
        System.out.println("=== Cross-Schema Reference Diagnostic ===\n");

        // Build indices with table from co_abs schema
        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("abs_werk_nr", new TransformationIndices.ColumnTypeInfo("NUMBER", null));
        columns.put("spa_abgelehnt_am", new TransformationIndices.ColumnTypeInfo("DATE", null));

        tableColumns.put("co_abs.abs_werk_sperren", columns);

        System.out.println("Table in indices: co_abs.abs_werk_sperren");
        System.out.println("Columns: abs_werk_nr (NUMBER), spa_abgelehnt_am (DATE)\n");

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            Collections.emptyMap(),
            Collections.emptySet()
        );

        // Simplified Oracle SQL
        String oracleSql = "SELECT ws1.spa_abgelehnt_am + 34 FROM co_abs.abs_werk_sperren ws1";

        System.out.println("Oracle SQL: " + oracleSql);
        System.out.println("Context schema: co_xm_pub_core\n");

        // Parse
        AntlrParser parser = new AntlrParser();
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);

        if (parseResult.hasErrors()) {
            System.err.println("Parse errors!");
            return;
        }

        // Run type analysis with DIFFERENT schema (co_xm_pub_core, not co_abs)
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("co_xm_pub_core", indices, typeCache);
        typeAnalyzer.visit(parseResult.getTree());

        // Analyze results
        System.out.println("=== Type Cache Results ===");
        System.out.println("Total entries: " + typeCache.size());

        long dateCount = typeCache.values().stream().filter(TypeInfo::isDate).count();
        long numericCount = typeCache.values().stream().filter(TypeInfo::isNumeric).count();
        long unknownCount = typeCache.values().stream().filter(TypeInfo::isUnknown).count();

        System.out.println("DATE types: " + dateCount);
        System.out.println("NUMERIC types: " + numericCount);
        System.out.println("UNKNOWN types: " + unknownCount);

        if (dateCount == 0) {
            System.out.println("\n❌ BUG CONFIRMED: spa_abgelehnt_am (DATE column) was not resolved!");
            System.out.println("This is because:");
            System.out.println("1. FROM co_abs.abs_werk_sperren ws1 → alias ws1 → abs_werk_sperren (schema stripped!)");
            System.out.println("2. Lookup ws1.spa_abgelehnt_am uses currentSchema='co_xm_pub_core'");
            System.out.println("3. Builds key: co_xm_pub_core.abs_werk_sperren (doesn't exist!)");
            System.out.println("4. Should build key: co_abs.abs_werk_sperren (exists!)");
        } else {
            System.out.println("\n✅ Column type resolved successfully");
            System.out.println("Either the bug doesn't exist, or there's a fallback mechanism we're not aware of.");
        }
    }
}
