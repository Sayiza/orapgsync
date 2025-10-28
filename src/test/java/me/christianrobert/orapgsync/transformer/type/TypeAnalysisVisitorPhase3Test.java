package me.christianrobert.orapgsync.transformer.type;

import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TypeAnalysisVisitor Phase 3: Built-in Functions.
 *
 * <p>Tests:</p>
 * <ul>
 *   <li>Polymorphic functions (ROUND, TRUNC with different types)</li>
 *   <li>Date functions (SYSDATE, ADD_MONTHS, MONTHS_BETWEEN)</li>
 *   <li>String functions (UPPER, LOWER, SUBSTR, LENGTH)</li>
 *   <li>Conversion functions (TO_CHAR, TO_DATE, TO_NUMBER)</li>
 *   <li>NULL-handling functions (NVL, COALESCE, DECODE)</li>
 *   <li>Aggregate functions (COUNT, SUM, AVG, MIN, MAX)</li>
 *   <li>Numeric functions (ABS, SQRT, etc.)</li>
 * </ul>
 */
class TypeAnalysisVisitorPhase3Test {

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

        return new TransformationIndices(
                tableColumns,
                new HashMap<>(),  // typeMethods
                new HashSet<>(),  // packageFunctions
                new HashMap<>()   // synonyms
        );
    }

    // ========== Polymorphic Functions (ROUND, TRUNC) ==========

    @Test
    void round_withNumericLiteral_shouldReturnNumeric() {
        // Given: ROUND with number
        String sql = "SELECT ROUND(123.456, 2) FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "ROUND(numeric) should return NUMERIC");
    }

    @Test
    void round_withDateColumn_shouldReturnDate() {
        // Given: ROUND with DATE column
        String sql = "SELECT ROUND(hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "ROUND(date) should return DATE");
    }

    @Test
    void trunc_withNumericColumn_shouldReturnNumeric() {
        // Given: TRUNC with NUMBER column
        String sql = "SELECT TRUNC(salary, 0) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "TRUNC(numeric) should return NUMERIC");
    }

    @Test
    void trunc_withDateColumn_shouldReturnDate() {
        // Given: TRUNC with DATE column
        String sql = "SELECT TRUNC(hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "TRUNC(date) should return DATE");
    }

    // ========== Date Functions ==========

    @Test
    void sysdate_shouldReturnDate() {
        // Given: SYSDATE pseudo-column
        String sql = "SELECT SYSDATE FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "SYSDATE should return DATE");
    }

    @Test
    void systimestamp_shouldReturnTimestamp() {
        // Given: SYSTIMESTAMP pseudo-column
        String sql = "SELECT SYSTIMESTAMP FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE (TypeInfo.TIMESTAMP is a DATE category)
        assertContainsType(TypeInfo.TypeCategory.DATE, "SYSTIMESTAMP should return DATE category");
    }

    @Test
    void addMonths_shouldReturnDate() {
        // Given: ADD_MONTHS function
        String sql = "SELECT ADD_MONTHS(hire_date, 6) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "ADD_MONTHS should return DATE");
    }

    @Test
    void monthsBetween_shouldReturnNumeric() {
        // Given: MONTHS_BETWEEN function
        String sql = "SELECT MONTHS_BETWEEN(SYSDATE, hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "MONTHS_BETWEEN should return NUMERIC");
    }

    @Test
    void lastDay_shouldReturnDate() {
        // Given: LAST_DAY function
        String sql = "SELECT LAST_DAY(hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "LAST_DAY should return DATE");
    }

    // ========== String Functions ==========

    @Test
    void upper_shouldReturnText() {
        // Given: UPPER function
        String sql = "SELECT UPPER(emp_name) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT, "UPPER should return TEXT");
    }

    @Test
    void lower_shouldReturnText() {
        // Given: LOWER function
        String sql = "SELECT LOWER(emp_name) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT, "LOWER should return TEXT");
    }

    @Test
    void substr_shouldReturnText() {
        // Given: SUBSTR function
        String sql = "SELECT SUBSTR(emp_name, 1, 10) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT, "SUBSTR should return TEXT");
    }

    @Test
    void length_shouldReturnNumeric() {
        // Given: LENGTH function
        String sql = "SELECT LENGTH(emp_name) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "LENGTH should return NUMERIC");
    }

    @Test
    void instr_shouldReturnNumeric() {
        // Given: INSTR function
        String sql = "SELECT INSTR(emp_name, 'John') FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "INSTR should return NUMERIC");
    }

    @Test
    void trim_shouldReturnText() {
        // Given: TRIM function
        String sql = "SELECT TRIM(emp_name) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT, "TRIM should return TEXT");
    }

    // ========== Conversion Functions ==========

    @Test
    void toChar_shouldReturnText() {
        // Given: TO_CHAR function with date
        String sql = "SELECT TO_CHAR(hire_date, 'YYYY-MM-DD') FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return TEXT
        assertContainsType(TypeInfo.TypeCategory.TEXT, "TO_CHAR should return TEXT");
    }

    @Test
    void toNumber_shouldReturnNumeric() {
        // Given: TO_NUMBER function
        String sql = "SELECT TO_NUMBER('123.45') FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "TO_NUMBER should return NUMERIC");
    }

    @Test
    void toDate_shouldReturnDate() {
        // Given: TO_DATE function
        String sql = "SELECT TO_DATE('2024-01-01', 'YYYY-MM-DD') FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "TO_DATE should return DATE");
    }

    @Test
    void toTimestamp_shouldReturnDate() {
        // Given: TO_TIMESTAMP function
        String sql = "SELECT TO_TIMESTAMP('2024-01-01 12:00:00', 'YYYY-MM-DD HH24:MI:SS') FROM dual";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE category (TypeInfo.TIMESTAMP)
        assertContainsType(TypeInfo.TypeCategory.DATE, "TO_TIMESTAMP should return DATE category");
    }

    // ========== NULL-Handling Functions ==========

    @Test
    void nvl_withNumeric_shouldReturnNumeric() {
        // Given: NVL with numeric arguments
        String sql = "SELECT NVL(salary, 0) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC (highest precedence of arguments)
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "NVL(numeric, numeric) should return NUMERIC");
    }

    @Test
    void nvl_withDate_shouldReturnDate() {
        // Given: NVL with DATE arguments
        String sql = "SELECT NVL(hire_date, SYSDATE) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "NVL(date, date) should return DATE");
    }

    @Test
    void coalesce_shouldReturnHighestPrecedence() {
        // Given: COALESCE with numeric arguments
        String sql = "SELECT COALESCE(salary, emp_id, 0) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "COALESCE should return highest precedence type");
    }

    @Test
    void decode_withNumericResults_shouldReturnNumeric() {
        // Given: DECODE with numeric results
        String sql = "SELECT DECODE(emp_id, 1, salary, 2, salary * 2, 0) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "DECODE with numeric results should return NUMERIC");
    }

    @Test
    void decode_withDateResults_shouldReturnDate() {
        // Given: DECODE with DATE results
        String sql = "SELECT DECODE(emp_id, 1, hire_date, 2, SYSDATE, hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "DECODE with date results should return DATE");
    }

    // ========== Aggregate Functions ==========

    @Test
    void count_shouldReturnNumeric() {
        // Given: COUNT function
        String sql = "SELECT COUNT(*) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "COUNT should return NUMERIC");
    }

    @Test
    void sum_shouldReturnNumeric() {
        // Given: SUM function
        String sql = "SELECT SUM(salary) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "SUM should return NUMERIC");
    }

    @Test
    void avg_shouldReturnNumeric() {
        // Given: AVG function
        String sql = "SELECT AVG(salary) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "AVG should return NUMERIC");
    }

    @Test
    void min_shouldReturnArgumentType() {
        // Given: MIN function with DATE
        String sql = "SELECT MIN(hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "MIN should return argument type");
    }

    @Test
    void max_shouldReturnArgumentType() {
        // Given: MAX function with DATE
        String sql = "SELECT MAX(hire_date) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return DATE
        assertContainsType(TypeInfo.TypeCategory.DATE, "MAX should return argument type");
    }

    // ========== Numeric Functions ==========

    @Test
    void abs_shouldReturnNumeric() {
        // Given: ABS function
        String sql = "SELECT ABS(salary) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "ABS should return NUMERIC");
    }

    @Test
    void sqrt_shouldReturnNumeric() {
        // Given: SQRT function
        String sql = "SELECT SQRT(salary) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "SQRT should return NUMERIC");
    }

    @Test
    void ceil_shouldReturnNumeric() {
        // Given: CEIL function
        String sql = "SELECT CEIL(salary) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "CEIL should return NUMERIC");
    }

    @Test
    void floor_shouldReturnNumeric() {
        // Given: FLOOR function
        String sql = "SELECT FLOOR(salary) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "FLOOR should return NUMERIC");
    }

    // ========== Complex Nested Functions ==========

    @Test
    void nestedFunctions_shouldPropagateTypes() {
        // Given: Nested functions
        String sql = "SELECT UPPER(SUBSTR(emp_name, 1, 10)) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: Both SUBSTR and UPPER should return TEXT
        long textCount = typeCache.values().stream()
            .filter(t -> t.getCategory() == TypeInfo.TypeCategory.TEXT)
            .count();

        assertTrue(textCount >= 2, "Should have at least 2 TEXT types (SUBSTR and UPPER)");
    }

    @Test
    void functionWithArithmetic_shouldCombineCorrectly() {
        // Given: Function result used in arithmetic
        String sql = "SELECT MONTHS_BETWEEN(SYSDATE, hire_date) / 12 FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: MONTHS_BETWEEN returns NUMERIC, division returns NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "Function result in arithmetic should propagate NUMERIC");
    }

    @Test
    void roundOfMonthsBetween_shouldReturnNumeric() {
        // Given: ROUND of MONTHS_BETWEEN (numeric result)
        String sql = "SELECT ROUND(MONTHS_BETWEEN(SYSDATE, hire_date) / 12, 2) FROM employees";
        ParseResult parseResult = parser.parseSelectStatement(sql);

        // When: Type analysis
        visitor.visit(parseResult.getTree());

        // Then: ROUND of numeric expression should return NUMERIC
        assertContainsType(TypeInfo.TypeCategory.NUMERIC, "ROUND of numeric expression should return NUMERIC");
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
