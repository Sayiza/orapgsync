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

        // Build indices with table from lending schema
        Map<String, Map<String, TransformationIndices.ColumnTypeInfo>> tableColumns = new HashMap<>();

        Map<String, TransformationIndices.ColumnTypeInfo> columns = new HashMap<>();
        columns.put("hold_item_nr", new TransformationIndices.ColumnTypeInfo("NUMBER", null));
        columns.put("hold_placed", new TransformationIndices.ColumnTypeInfo("DATE", null));

        tableColumns.put("lending.item_holds", columns);

        System.out.println("Table in indices: lending.item_holds");
        System.out.println("Columns: hold_item_nr (NUMBER), hold_placed (DATE)\n");

        TransformationIndices indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(),
            new HashSet<>(),
            new HashMap<>(),
            Collections.emptyMap(),
            Collections.emptySet()
        );

        // Simplified Oracle SQL
        String oracleSql = "SELECT ws1.hold_placed + 34 FROM lending.item_holds ws1";

        System.out.println("Oracle SQL: " + oracleSql);
        System.out.println("Context schema: holds_admin\n");

        // Parse
        AntlrParser parser = new AntlrParser();
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);

        if (parseResult.hasErrors()) {
            System.err.println("Parse errors!");
            return;
        }

        // Run type analysis with DIFFERENT schema (holds_admin, not lending)
        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor typeAnalyzer = new TypeAnalysisVisitor("holds_admin", indices, typeCache);
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
            System.out.println("\n❌ BUG CONFIRMED: hold_placed (DATE column) was not resolved!");
            System.out.println("This is because:");
            System.out.println("1. FROM lending.item_holds ws1 → alias ws1 → item_holds (schema stripped!)");
            System.out.println("2. Lookup ws1.hold_placed uses currentSchema='holds_admin'");
            System.out.println("3. Builds key: holds_admin.item_holds (doesn't exist!)");
            System.out.println("4. Should build key: lending.item_holds (exists!)");
        } else {
            System.out.println("\n✅ Column type resolved successfully");
            System.out.println("Either the bug doesn't exist, or there's a fallback mechanism we're not aware of.");
        }
    }
}
