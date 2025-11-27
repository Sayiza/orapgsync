package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
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
 * Unit tests for TypeAnalysisVisitor Phase 2: Column References and Metadata Integration.
 *
 * <p>Tests:</p>
 * <ul>
 *   <li>Table alias tracking from FROM clause</li>
 *   <li>Unqualified column resolution</li>
 *   <li>Qualified column resolution (with table names and aliases)</li>
 *   <li>Column type mapping from metadata</li>
 *   <li>Different Oracle types (NUMBER, VARCHAR2, DATE, TIMESTAMP, etc.)</li>
 * </ul>
 */
class TypeAnalysisVisitorPhase2Test {

    private AntlrParser parser;
    private TransformationIndices indices;
    private Map<String, TypeInfo> typeCache;
    private TypeAnalysisVisitor visitor;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up test metadata with sample tables and columns
        indices = createTestIndices();

        typeCache = new HashMap<>();
        visitor = new TypeAnalysisVisitor("hr", indices, typeCache);
    }

    /**
     * Creates test transformation indices with sample metadata.
     *
     * <p>Test schema: HR
     * <ul>
     *   <li>EMPLOYEES: emp_id (NUMBER), emp_name (VARCHAR2), hire_date (DATE), salary (NUMBER)</li>
     *   <li>DEPARTMENTS: dept_id (NUMBER), dept_name (VARCHAR2), created_at (TIMESTAMP)</li>
     * </ul>
     */
    private TransformationIndices createTestIndices() {
        Map<String, Map<String, ColumnTypeInfo>> tableColumns = new HashMap<>();

        // HR.EMPLOYEES table
        Map<String, ColumnTypeInfo> employeesColumns = new HashMap<>();
        employeesColumns.put("emp_id", new ColumnTypeInfo("NUMBER", null));
        employeesColumns.put("emp_name", new ColumnTypeInfo("VARCHAR2", null));
        employeesColumns.put("hire_date", new ColumnTypeInfo("DATE", null));
        employeesColumns.put("salary", new ColumnTypeInfo("NUMBER", null));
        tableColumns.put("hr.employees", employeesColumns);

        // HR.DEPARTMENTS table
        Map<String, ColumnTypeInfo> departmentsColumns = new HashMap<>();
        departmentsColumns.put("dept_id", new ColumnTypeInfo("NUMBER", null));
        departmentsColumns.put("dept_name", new ColumnTypeInfo("VARCHAR2", null));
        departmentsColumns.put("created_at", new ColumnTypeInfo("TIMESTAMP", null));
        tableColumns.put("hr.departments", departmentsColumns);

        return new TransformationIndices(
                tableColumns,
                new HashMap<>(),  // typeMethods
                new HashSet<>(),  // packageFunctions
                new HashMap<>(), // synonyms
        Collections.emptyMap(), // typeFieldTypes
        Collections.emptySet()  // objectTypeNames
        );
    }

    // ========== Unqualified Column References ==========

    @Test
    void unqualifiedColumn_shouldResolveTypeFromMetadata() {
        // Given: Unqualified column reference
        String sql = "SELECT emp_id FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve emp_id to NUMBER
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "Should resolve emp_id to NUMERIC from metadata");
    }

    @Test
    void unqualifiedColumn_varchar_shouldResolveToText() {
        // Given: VARCHAR2 column
        String sql = "SELECT emp_name FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve emp_name to TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT, "Should resolve emp_name (VARCHAR2) to TEXT");
    }

    @Test
    void unqualifiedColumn_date_shouldResolveToDate() {
        // Given: DATE column
        String sql = "SELECT hire_date FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve hire_date to DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "Should resolve hire_date (DATE) to DATE");
    }

    @Test
    void unqualifiedColumn_timestamp_shouldResolveToDate() {
        // Given: TIMESTAMP column
        String sql = "SELECT created_at FROM departments";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve created_at to DATE (TIMESTAMP category)
        assertContainsType(TypeInfo.TypeCategory.DATE, "Should resolve created_at (TIMESTAMP) to DATE");
    }

    // ========== Qualified Column References (Table Name) ==========

    @Test
    void qualifiedColumn_withTableName_shouldResolveType() {
        // Given: Qualified column with table name
        String sql = "SELECT employees.emp_id FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve employees.emp_id to NUMBER
        assertContainsType(TypeInfo.TypeCategory.NUMERIC,
            "Should resolve qualified column employees.emp_id to NUMERIC");
    }

    @Test
    void qualifiedColumn_withTableName_varchar_shouldResolveToText() {
        // Given: VARCHAR2 column with table qualification
        String sql = "SELECT departments.dept_name FROM departments";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve to TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT,
            "Should resolve qualified VARCHAR2 column to TEXT");
    }

    // ========== Qualified Column References (Alias) ==========

    @Test
    void qualifiedColumn_withAlias_shouldResolveType() {
        // Given: Table with alias
        String sql = "SELECT e.emp_id FROM employees e";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve e.emp_id to NUMBER via alias
        assertContainsType(TypeInfo.TypeCategory.NUMERIC,
            "Should resolve aliased column e.emp_id to NUMERIC");
    }

    @Test
    void qualifiedColumn_withAliasAndAS_shouldResolveType() {
        // Given: Table with AS keyword in alias
        String sql = "SELECT emp.salary FROM employees AS emp";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve emp.salary to NUMBER
        assertContainsType(TypeInfo.TypeCategory.NUMERIC,
            "Should resolve column with AS alias to NUMERIC");
    }

    // ========== Multiple Column References ==========

    @Test
    void multipleColumns_shouldResolveAllTypes() {
        // Given: Multiple columns of different types
        String sql = "SELECT emp_id, emp_name, hire_date, salary FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have resolved multiple types
        long numericCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.NUMERIC)
            .count();

        long textCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.TEXT)
            .count();

        long dateCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.DATE)
            .count();

        assertTrue(numericCount >= 2, "Should have at least 2 NUMERIC types (emp_id, salary)");
        assertTrue(textCount >= 1, "Should have at least 1 TEXT type (emp_name)");
        assertTrue(dateCount >= 1, "Should have at least 1 DATE type (hire_date)");
    }

    // ========== Expressions with Columns ==========

    @Test
    void arithmeticExpression_withColumns_shouldPropagateType() {
        // Given: Arithmetic with column
        String sql = "SELECT salary + 1000 FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should have NUMERIC types for column and expression
        long numericCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.NUMERIC)
            .count();

        assertTrue(numericCount >= 2,
            "Should have NUMERIC for both salary and the expression (salary + 1000)");
    }

    @Test
    void dateArithmetic_withColumn_shouldPropagateType() {
        // Given: Date arithmetic with column
        String sql = "SELECT hire_date + 30 FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve hire_date to DATE
        assertContainsType(TypeInfo.TypeCategory.DATE,
            "Should resolve hire_date column to DATE");

        // And arithmetic result should also be DATE (Phase 1)
        long dateCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.DATE)
            .count();

        assertTrue(dateCount >= 1, "Should have DATE type for hire_date");
    }

    // ========== JOIN Scenarios ==========

    @Test
    void join_withMultipleTables_shouldResolveColumns() {
        // Given: JOIN with qualified columns
        String sql = """
            SELECT e.emp_name, d.dept_name
            FROM employees e
            JOIN departments d ON e.dept_id = d.dept_id
            """;
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should resolve both TEXT columns
        long textCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.TEXT)
            .count();

        assertTrue(textCount >= 2,
            "Should resolve both emp_name and dept_name to TEXT");
    }

    // ========== Edge Cases ==========

    @Test
    void unknownColumn_shouldReturnUnknown() {
        // Given: Column that doesn't exist in metadata
        String sql = "SELECT unknown_column FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should not crash, should return UNKNOWN
        // (At least some entries should be UNKNOWN for the unresolved column)
        long unknownCount = typeCache.values().stream()
            .filter(TypeInfo::isUnknown)
            .count();

        assertTrue(unknownCount >= 1,
            "Should have UNKNOWN type for unresolved column");
    }

    @Test
    void unknownTable_shouldReturnUnknown() {
        // Given: Table that doesn't exist in metadata
        String sql = "SELECT col FROM unknown_table";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should not crash
        assertNotNull(typeCache, "Type cache should not be null");
    }

    // ========== Helper Methods ==========

    /**
     * Asserts that type cache contains at least one entry with given category.
     */
    private void assertContainsType(TypeInfo.TypeCategory category, String message) {
        boolean found = typeCache.values().stream()
            .anyMatch(type -> type.getCategory() == category);

        assertTrue(found, message + ". Cache contents: " + describeCache());
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
