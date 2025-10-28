package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Diagnostic test to debug Phase 2 column resolution.
 */
class Phase2DiagnosticTest {

    @Test
    void diagnosticTest() {
        AntlrParser parser = new AntlrParser();

        // Create minimal test indices
        Map<String, Map<String, ColumnTypeInfo>> tableColumns = new HashMap<>();
        Map<String, ColumnTypeInfo> employeesColumns = new HashMap<>();
        employeesColumns.put("emp_id", new ColumnTypeInfo("NUMBER", null));
        tableColumns.put("hr.employees", employeesColumns);

        TransformationIndices indices = new TransformationIndices(
            tableColumns, new HashMap<>(), new HashSet<>(), new HashMap<>()
        );

        Map<String, TypeInfo> typeCache = new HashMap<>();
        TypeAnalysisVisitor visitor = new TypeAnalysisVisitor("hr", indices, typeCache);

        String sql = "SELECT emp_id FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        System.out.println("=== Before visiting ===");
        System.out.println("Type cache size: " + typeCache.size());

        visitor.visit(parseResult.getTree());

        System.out.println("\n=== After visiting ===");
        System.out.println("Type cache size: " + typeCache.size());
        System.out.println("Type cache entries:");
        typeCache.forEach((k, v) -> {
            System.out.println("  " + k + " -> " + v.getCategory());
        });

        System.out.println("\n=== Checking indices ===");
        System.out.println("Tables in indices: " + indices.getAllTableColumns().keySet());
        System.out.println("Columns in hr.employees: " + indices.getAllTableColumns().get("hr.employees").keySet());

        System.out.println("\n=== Testing column lookup directly ===");
        ColumnTypeInfo colInfo = indices.getColumnType("hr.employees", "emp_id");
        System.out.println("Direct lookup result: " + (colInfo != null ? colInfo.getTypeName() : "null"));
    }
}
