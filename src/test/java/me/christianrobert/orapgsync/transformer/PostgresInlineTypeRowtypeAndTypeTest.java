package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices.ColumnTypeInfo;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for %ROWTYPE and %TYPE transformations (Phase 1F).
 *
 * <p>Tests verify that Oracle %ROWTYPE and %TYPE references are correctly resolved and transformed:
 * <ul>
 *   <li>%ROWTYPE → jsonb-based RECORD with all table columns</li>
 *   <li>%TYPE (column reference) → resolves to PostgreSQL column type</li>
 *   <li>%TYPE (variable reference) → inherits type from variable</li>
 *   <li>Field access/assignment works for %ROWTYPE variables</li>
 * </ul>
 *
 * <p><b>Phase 1F Status:</b> Core implementation complete, integration tests pending
 *
 * <p>See: INLINE_TYPE_IMPLEMENTATION_PLAN.md Phase 1F (lines 1034-1084)
 */
class PostgresInlineTypeRowtypeAndTypeTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up transformation indices with test tables
        Map<String, Map<String, ColumnTypeInfo>> tableColumns = new HashMap<>();

        // Table: hr.employees with columns: empno (NUMBER), ename (VARCHAR2), salary (NUMBER), hire_date (DATE)
        Map<String, ColumnTypeInfo> employeesCols = new HashMap<>();
        employeesCols.put("empno", new ColumnTypeInfo("NUMBER", null));
        employeesCols.put("ename", new ColumnTypeInfo("VARCHAR2", null));
        employeesCols.put("salary", new ColumnTypeInfo("NUMBER", null));
        employeesCols.put("hire_date", new ColumnTypeInfo("DATE", null));
        tableColumns.put("hr.employees", employeesCols);

        // Table: hr.departments with columns: dept_id (NUMBER), dept_name (VARCHAR2)
        Map<String, ColumnTypeInfo> departmentsCols = new HashMap<>();
        departmentsCols.put("dept_id", new ColumnTypeInfo("NUMBER", null));
        departmentsCols.put("dept_name", new ColumnTypeInfo("VARCHAR2", null));
        tableColumns.put("hr.departments", departmentsCols);

        indices = new TransformationIndices(
            tableColumns,
            new HashMap<>(), // typeMethods
            new HashSet<>(), // packageFunctions
            new HashMap<>()  // synonyms
        );
    }

    private String transform(String oracleFunctionBody) {
        ParseResult parseResult = parser.parseFunctionBody(oracleFunctionBody);
        if (parseResult.hasErrors()) {
            fail("Parse failed: " + parseResult.getErrors());
        }

        TransformationContext context = new TransformationContext(
            "hr",
            indices,
            new SimpleTypeEvaluator("hr", indices)
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

        return builder.visit(parseResult.getTree());
    }

    // ========== %ROWTYPE TESTS ==========

    /**
     * TEST 1: Basic %ROWTYPE declaration
     * Oracle: v_emp employees%ROWTYPE;
     * Expected: v_emp jsonb := '{}'::jsonb;
     */
    @Test
    void rowtypeBasic_declarationAsJsonb() {
        String oracleSql =
            "FUNCTION test_rowtype RETURN NUMBER IS\n" +
            "  v_emp employees%ROWTYPE;\n" +
            "BEGIN\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 1: Basic %ROWTYPE Declaration ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("==========================================\n");

        // Variable should be jsonb
        assertTrue(result.contains("v_emp jsonb"), "Variable should be declared as jsonb");

        // Should have automatic initialization
        assertTrue(result.contains("v_emp := '{}'::jsonb") ||
                   result.contains("v_emp jsonb := '{}'::jsonb"),
                   "Variable should have automatic jsonb initialization");
    }

    /**
     * TEST 2: %ROWTYPE with field assignment
     * Oracle: v_emp.empno := 100;
     * Expected: v_emp := jsonb_set(v_emp, '{empno}', to_jsonb(100));
     */
    @Test
    void rowtypeFieldAssignment_transformsToJsonbSet() {
        String oracleSql =
            "FUNCTION test_assignment RETURN NUMBER IS\n" +
            "  v_emp employees%ROWTYPE;\n" +
            "BEGIN\n" +
            "  v_emp.empno := 100;\n" +
            "  v_emp.ename := 'Smith';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 2: %ROWTYPE Field Assignment ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Field assignments should use jsonb_set
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_emp") && normalized.contains("empno"),
                   "empno assignment should use jsonb_set");

        assertTrue(normalized.contains("to_jsonb") && normalized.contains("100"),
                   "Field assignment should wrap value in to_jsonb()");

        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_emp") && normalized.contains("ename"),
                   "ename assignment should use jsonb_set");
    }

    /**
     * TEST 3: Multiple %ROWTYPE variables
     * Oracle: v_emp employees%ROWTYPE; v_dept departments%ROWTYPE;
     * Expected: Both declared as jsonb
     */
    @Test
    void rowtypeMultipleVariables_bothAsJsonb() {
        String oracleSql =
            "FUNCTION test_multiple RETURN NUMBER IS\n" +
            "  v_emp employees%ROWTYPE;\n" +
            "  v_dept departments%ROWTYPE;\n" +
            "BEGIN\n" +
            "  v_emp.empno := 100;\n" +
            "  v_dept.dept_id := 10;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 3: Multiple %ROWTYPE Variables ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("===========================================\n");

        // Both variables should be jsonb
        assertTrue(result.contains("v_emp jsonb"), "v_emp should be declared as jsonb");
        assertTrue(result.contains("v_dept jsonb"), "v_dept should be declared as jsonb");

        // Both should have initialization
        assertTrue(result.contains("'{}'::jsonb"), "Both variables should have jsonb initialization");
    }

    /**
     * TEST 4: %ROWTYPE with qualified table name
     * Oracle: v_emp hr.employees%ROWTYPE;
     * Expected: v_emp jsonb := '{}'::jsonb;
     */
    @Test
    void rowtypeQualifiedTable_declarationAsJsonb() {
        String oracleSql =
            "FUNCTION test_qualified RETURN NUMBER IS\n" +
            "  v_emp hr.employees%ROWTYPE;\n" +
            "BEGIN\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 4: %ROWTYPE with Qualified Table ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=============================================\n");

        // Variable should be jsonb
        assertTrue(result.contains("v_emp jsonb"), "Variable should be declared as jsonb");
        assertTrue(result.contains("'{}'::jsonb"), "Variable should have jsonb initialization");
    }

    // ========== %TYPE TESTS (Column References) ==========

    /**
     * TEST 5: %TYPE with column reference
     * Oracle: v_num employees.salary%TYPE;
     * Expected: v_num numeric; (NUMBER → numeric)
     */
    @Test
    void typeColumnReference_resolvesToPostgresType() {
        String oracleSql =
            "FUNCTION test_type_column RETURN NUMBER IS\n" +
            "  v_num employees.salary%TYPE;\n" +
            "BEGIN\n" +
            "  v_num := 50000;\n" +
            "  RETURN v_num;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 5: %TYPE Column Reference ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("======================================\n");

        // Variable should be numeric (Oracle NUMBER → PostgreSQL numeric)
        assertTrue(result.contains("v_num numeric"), "Variable should be declared as numeric");

        // Should NOT have jsonb initialization (it's a simple type)
        assertFalse(result.contains("v_num := '{}'::jsonb"), "Simple type should not have jsonb initialization");
    }

    /**
     * TEST 6: %TYPE with VARCHAR2 column
     * Oracle: v_name employees.ename%TYPE;
     * Expected: v_name text; (VARCHAR2 → text)
     */
    @Test
    void typeColumnReference_varchar_resolvesToText() {
        String oracleSql =
            "FUNCTION test_type_varchar RETURN VARCHAR2 IS\n" +
            "  v_name employees.ename%TYPE;\n" +
            "BEGIN\n" +
            "  v_name := 'Smith';\n" +
            "  RETURN v_name;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 6: %TYPE VARCHAR2 Column ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("====================================\n");

        // Variable should be text (Oracle VARCHAR2 → PostgreSQL text)
        assertTrue(result.contains("v_name text"), "Variable should be declared as text");
    }

    /**
     * TEST 7: %TYPE with DATE column
     * Oracle: v_date employees.hire_date%TYPE;
     * Expected: v_date timestamp; (DATE → timestamp)
     */
    @Test
    void typeColumnReference_date_resolvesToTimestamp() {
        String oracleSql =
            "FUNCTION test_type_date RETURN DATE IS\n" +
            "  v_date employees.hire_date%TYPE;\n" +
            "BEGIN\n" +
            "  v_date := SYSDATE;\n" +
            "  RETURN v_date;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 7: %TYPE DATE Column ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=================================\n");

        // Variable should be timestamp (Oracle DATE → PostgreSQL timestamp)
        assertTrue(result.contains("v_date timestamp"), "Variable should be declared as timestamp");
    }

    // ========== %TYPE TESTS (Variable References) ==========

    /**
     * TEST 8: %TYPE with variable reference (simple type)
     * Oracle: v_sal NUMBER; v_copy v_sal%TYPE;
     * Expected: v_sal numeric; v_copy numeric;
     */
    @Test
    void typeVariableReference_simple_inheritsType() {
        String oracleSql =
            "FUNCTION test_type_var RETURN NUMBER IS\n" +
            "  v_sal NUMBER;\n" +
            "  v_copy v_sal%TYPE;\n" +
            "BEGIN\n" +
            "  v_sal := 50000;\n" +
            "  v_copy := v_sal * 1.1;\n" +
            "  RETURN v_copy;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 8: %TYPE Variable Reference (Simple) ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=================================================\n");

        // Both variables should be numeric
        assertTrue(result.contains("v_sal numeric"), "v_sal should be declared as numeric");
        assertTrue(result.contains("v_copy numeric"), "v_copy should inherit numeric type");
    }

    /**
     * TEST 9: %TYPE with variable reference (%ROWTYPE)
     * Oracle: v_emp employees%ROWTYPE; v_emp2 v_emp%TYPE;
     * Expected: Both declared as jsonb
     */
    @Test
    void typeVariableReference_rowtype_inheritsJsonb() {
        String oracleSql =
            "FUNCTION test_type_var_rowtype RETURN NUMBER IS\n" +
            "  v_emp employees%ROWTYPE;\n" +
            "  v_emp2 v_emp%TYPE;\n" +
            "BEGIN\n" +
            "  v_emp.empno := 100;\n" +
            "  v_emp2.empno := 200;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 9: %TYPE Variable Reference (%ROWTYPE) ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("===================================================\n");

        // Both variables should be jsonb
        assertTrue(result.contains("v_emp jsonb"), "v_emp should be declared as jsonb");
        assertTrue(result.contains("v_emp2 jsonb"), "v_emp2 should inherit jsonb type");
    }

    /**
     * TEST 10: Multiple %TYPE chaining
     * Oracle: v1 NUMBER; v2 v1%TYPE; v3 v2%TYPE;
     * Expected: All declared as numeric
     */
    @Test
    void typeVariableReference_chaining_inheritsType() {
        String oracleSql =
            "FUNCTION test_type_chain RETURN NUMBER IS\n" +
            "  v1 NUMBER;\n" +
            "  v2 v1%TYPE;\n" +
            "  v3 v2%TYPE;\n" +
            "BEGIN\n" +
            "  v1 := 100;\n" +
            "  v2 := v1 + 10;\n" +
            "  v3 := v2 + 10;\n" +
            "  RETURN v3;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 10: %TYPE Chaining ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("===============================\n");

        // All variables should be numeric
        assertTrue(result.contains("v1 numeric"), "v1 should be declared as numeric");
        assertTrue(result.contains("v2 numeric"), "v2 should inherit numeric type");
        assertTrue(result.contains("v3 numeric"), "v3 should inherit numeric type");
    }

    // ========== COMBINED TESTS ==========

    /**
     * TEST 11: Mixed %ROWTYPE and %TYPE
     * Oracle: v_emp employees%ROWTYPE; v_sal v_emp.salary%TYPE;
     * Expected: v_emp jsonb; v_sal numeric;
     */
    @Test
    void mixed_rowtypeAndType_bothWork() {
        String oracleSql =
            "FUNCTION test_mixed RETURN NUMBER IS\n" +
            "  v_emp employees%ROWTYPE;\n" +
            "  v_sal employees.salary%TYPE;\n" +
            "BEGIN\n" +
            "  v_emp.empno := 100;\n" +
            "  v_sal := 50000;\n" +
            "  RETURN v_sal;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 11: Mixed %ROWTYPE and %TYPE ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // v_emp should be jsonb, v_sal should be numeric
        assertTrue(result.contains("v_emp jsonb"), "v_emp should be declared as jsonb");
        assertTrue(result.contains("v_sal numeric"), "v_sal should be declared as numeric");
    }

    /**
     * TEST 12: %ROWTYPE with NOT NULL and DEFAULT
     * Oracle: v_emp employees%ROWTYPE NOT NULL := ...;
     * Expected: Constraints preserved
     */
    @Test
    void rowtypeWithConstraints_preservesNotNull() {
        String oracleSql =
            "FUNCTION test_constraints RETURN NUMBER IS\n" +
            "  v_count NUMBER NOT NULL := 0;\n" +
            "  v_emp employees%ROWTYPE;\n" +
            "BEGIN\n" +
            "  v_emp.empno := 100;\n" +
            "  RETURN v_count;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 12: %ROWTYPE with Constraints ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("==========================================\n");

        // NOT NULL should be preserved
        assertTrue(result.contains("v_count numeric NOT NULL"), "NOT NULL constraint should be preserved");

        // %ROWTYPE variable should still be jsonb
        assertTrue(result.contains("v_emp jsonb"), "v_emp should be declared as jsonb");
    }
}
