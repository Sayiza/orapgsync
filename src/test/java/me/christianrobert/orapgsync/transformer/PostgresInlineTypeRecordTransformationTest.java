package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for inline RECORD type transformation (Phase 1B).
 *
 * <p>Tests verify that Oracle inline RECORD types are correctly transformed to
 * PostgreSQL jsonb with proper field assignment handling:
 * <ul>
 *   <li>TYPE declarations → commented out and registered in context</li>
 *   <li>Variable declarations → jsonb with automatic initialization</li>
 *   <li>Field assignments (LHS) → jsonb_set transformations</li>
 *   <li>Nested field assignments → jsonb_set with path arrays</li>
 * </ul>
 *
 * <p><b>Phase 1B Status:</b> LHS (field assignment) complete, RHS (field access) deferred to Phase 1B.5
 *
 * <p>See: INLINE_TYPE_IMPLEMENTATION_PLAN.md Phase 1B (lines 538-595)
 */
class PostgresInlineTypeRecordTransformationTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up empty transformation indices
        indices = new TransformationIndices(
            new HashMap<>(), // tableColumns
            new HashMap<>(), // typeMethods
            new HashSet<>(), // packageFunctions
            new HashMap<>(), // synonyms
        Collections.emptyMap(), // typeFieldTypes
        Collections.emptySet()  // objectTypeNames
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

    // ========== TEST 1: Simple RECORD Type Declaration ==========

    @Test
    void simpleRecordType_declarationCommentedOut() {
        String oracleSql =
            "FUNCTION test_record RETURN NUMBER IS\n" +
            "  TYPE salary_range_t IS RECORD (\n" +
            "    min_sal NUMBER,\n" +
            "    max_sal NUMBER\n" +
            "  );\n" +
            "  v_range salary_range_t;\n" +
            "BEGIN\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 1: Simple RECORD Type Declaration ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("==============================================\n");

        // TYPE declaration should be commented out
        assertTrue(result.contains("-- TYPE salary_range_t IS RECORD") ||
                   result.contains("/* TYPE salary_range_t IS RECORD") ||
                   !result.toUpperCase().contains("TYPE SALARY_RANGE_T IS RECORD"),
                   "TYPE declaration should be commented out or removed");

        // Variable should be jsonb
        assertTrue(result.contains("v_range jsonb"), "Variable should be declared as jsonb");

        // Should have automatic initialization
        assertTrue(result.contains("v_range := '{}'::jsonb") ||
                   result.contains("v_range jsonb := '{}'::jsonb"),
                   "Variable should have automatic jsonb initialization");
    }

    // ========== TEST 2: Simple Field Assignment (LHS) ==========

    @Test
    void simpleFieldAssignment_transformsToJsonbSet() {
        String oracleSql =
            "FUNCTION test_assignment RETURN NUMBER IS\n" +
            "  TYPE salary_range_t IS RECORD (\n" +
            "    min_sal NUMBER,\n" +
            "    max_sal NUMBER\n" +
            "  );\n" +
            "  v_range salary_range_t;\n" +
            "BEGIN\n" +
            "  v_range.min_sal := 50000;\n" +
            "  v_range.max_sal := 150000;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 2: Simple Field Assignment ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=======================================\n");

        // Field assignments should use jsonb_set (flexible whitespace matching)
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_range") && normalized.contains("min_sal"),
                   "min_sal assignment should use jsonb_set");

        assertTrue(normalized.contains("to_jsonb") && normalized.contains("50000"),
                   "Field assignment should wrap value in to_jsonb()");

        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_range") && normalized.contains("max_sal"),
                   "max_sal assignment should use jsonb_set");
    }

    // ========== TEST 3: Nested Field Assignment ==========

    @Test
    void nestedFieldAssignment_transformsWithPathArrays() {
        String oracleSql =
            "FUNCTION test_nested RETURN NUMBER IS\n" +
            "  TYPE address_t IS RECORD (\n" +
            "    street VARCHAR2(100),\n" +
            "    city VARCHAR2(50)\n" +
            "  );\n" +
            "  TYPE employee_t IS RECORD (\n" +
            "    empno NUMBER,\n" +
            "    address address_t\n" +
            "  );\n" +
            "  v_emp employee_t;\n" +
            "BEGIN\n" +
            "  v_emp.address.city := 'Boston';\n" +
            "  v_emp.address.street := '123 Main St';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 3: Nested Field Assignment ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("========================================\n");

        // Nested field assignments should use jsonb_set with both fields in path
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_emp") &&
                   normalized.contains("address") && normalized.contains("city"),
                   "Nested assignment should use jsonb_set with address and city");

        // Should have create_if_missing parameter (true) for nested paths
        assertTrue(normalized.contains("true"),
                   "Nested jsonb_set should have create_if_missing=true parameter");
    }

    // ========== TEST 4: Multiple RECORD Variables ==========

    @Test
    void multipleRecordVariables_allConvertToJsonb() {
        String oracleSql =
            "FUNCTION test_multiple RETURN NUMBER IS\n" +
            "  TYPE config_t IS RECORD (timeout NUMBER, retries NUMBER);\n" +
            "  TYPE status_t IS RECORD (code NUMBER, message VARCHAR2(100));\n" +
            "  v_config config_t;\n" +
            "  v_status status_t;\n" +
            "BEGIN\n" +
            "  v_config.timeout := 30;\n" +
            "  v_status.code := 200;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 4: Multiple RECORD Variables ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Both variables should be jsonb
        assertTrue(result.contains("v_config jsonb"), "v_config should be jsonb");
        assertTrue(result.contains("v_status jsonb"), "v_status should be jsonb");

        // Both should have assignments transformed
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_config"),
                   "v_config assignment should use jsonb_set");
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_status"),
                   "v_status assignment should use jsonb_set");
    }

    // ========== TEST 5: RECORD with Various Oracle Types ==========

    @Test
    void recordWithVariousTypes_allFieldTypesSupported() {
        String oracleSql =
            "FUNCTION test_types RETURN NUMBER IS\n" +
            "  TYPE all_types_t IS RECORD (\n" +
            "    numeric_field NUMBER,\n" +
            "    text_field VARCHAR2(200),\n" +
            "    date_field DATE,\n" +
            "    integer_field INTEGER\n" +
            "  );\n" +
            "  v_data all_types_t;\n" +
            "BEGIN\n" +
            "  v_data.numeric_field := 12345.67;\n" +
            "  v_data.text_field := 'Test';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 5: RECORD with Various Types ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Variable should be jsonb
        assertTrue(result.contains("v_data jsonb"), "v_data should be jsonb");

        // All field types should work with to_jsonb()
        assertTrue(normalized.contains("to_jsonb") && normalized.contains("12345.67"),
                   "Numeric value should use to_jsonb");
        assertTrue(normalized.contains("to_jsonb") && normalized.contains("Test"),
                   "String value should use to_jsonb");
    }

    // ========== TEST 6: Deep Nested RECORD (3 Levels) ==========

    @Test
    void deepNestedRecord_threeLevelPathArrays() {
        String oracleSql =
            "FUNCTION test_deep_nested RETURN NUMBER IS\n" +
            "  TYPE person_t IS RECORD (name VARCHAR2(100), title VARCHAR2(50));\n" +
            "  TYPE department_t IS RECORD (dept_name VARCHAR2(100), manager person_t);\n" +
            "  TYPE company_t IS RECORD (company_name VARCHAR2(200), department department_t);\n" +
            "  v_company company_t;\n" +
            "BEGIN\n" +
            "  v_company.department.manager.name := 'Jane Doe';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 6: Deep Nested RECORD (3 Levels) ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("==============================================\n");

        // Should have 3-level path with department, manager, and name
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("department") &&
                   normalized.contains("manager") && normalized.contains("name"),
                   "Three-level nested assignment should use jsonb_set with all three path elements");
    }

    // ========== TEST 7: Empty RECORD Variable (No Assignments) ==========

    @Test
    void emptyRecordVariable_stillInitialized() {
        String oracleSql =
            "FUNCTION test_empty RETURN NUMBER IS\n" +
            "  TYPE config_t IS RECORD (setting1 VARCHAR2(50), setting2 NUMBER);\n" +
            "  v_config config_t;\n" +
            "  v_unused NUMBER;\n" +
            "BEGIN\n" +
            "  v_unused := 100;\n" +
            "  RETURN v_unused;\n" +
            "END;";

        String result = transform(oracleSql);

        System.out.println("\n=== TEST 7: Empty RECORD Variable ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=====================================\n");

        // Even unused RECORD variables should be initialized
        assertTrue(result.contains("v_config jsonb"), "v_config should be jsonb");
        assertTrue(result.contains("v_config := '{}'::jsonb") ||
                   result.contains("v_config jsonb := '{}'::jsonb"),
                   "Even unused RECORD variable should have initialization");
    }

    // ========== TEST 8: RECORD in IF Statement ==========

    @Test
    void recordInIfStatement_transformsCorrectly() {
        String oracleSql =
            "FUNCTION test_if RETURN NUMBER IS\n" +
            "  TYPE config_t IS RECORD (enabled VARCHAR2(1), timeout NUMBER);\n" +
            "  v_config config_t;\n" +
            "BEGIN\n" +
            "  IF 1 = 1 THEN\n" +
            "    v_config.enabled := 'Y';\n" +
            "    v_config.timeout := 30;\n" +
            "  END IF;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 8: RECORD in IF Statement ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("======================================\n");

        // Assignments inside IF should still use jsonb_set
        assertTrue(result.contains("IF"), "Should have IF statement");
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_config"),
                   "Field assignment in IF should use jsonb_set");
    }

    // ========== TEST 9: RECORD in LOOP ==========

    @Test
    void recordInLoop_transformsCorrectly() {
        String oracleSql =
            "FUNCTION test_loop RETURN NUMBER IS\n" +
            "  TYPE counter_t IS RECORD (value NUMBER, label VARCHAR2(50));\n" +
            "  v_counter counter_t;\n" +
            "  i NUMBER;\n" +
            "BEGIN\n" +
            "  FOR i IN 1..5 LOOP\n" +
            "    v_counter.value := i;\n" +
            "    v_counter.label := 'Count';\n" +
            "  END LOOP;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 9: RECORD in LOOP ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("==============================\n");

        // Assignments inside LOOP should use jsonb_set
        assertTrue(result.contains("FOR"), "Should have FOR loop");
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_counter"),
                   "Field assignment in LOOP should use jsonb_set");
    }

    // ========== TEST 10: Single Field RECORD ==========

    @Test
    void singleFieldRecord_stillWorksCorrectly() {
        String oracleSql =
            "FUNCTION test_single_field RETURN NUMBER IS\n" +
            "  TYPE simple_t IS RECORD (value NUMBER);\n" +
            "  v_simple simple_t;\n" +
            "BEGIN\n" +
            "  v_simple.value := 42;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 10: Single Field RECORD ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("====================================\n");

        // Single-field RECORD should work like multi-field
        assertTrue(result.contains("v_simple jsonb"), "v_simple should be jsonb");
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_simple") && normalized.contains("value"),
                   "Single field assignment should use jsonb_set");
    }

    // ========== TEST 11: RECORD with String Field Assignment ==========

    @Test
    void recordWithStringField_quotesHandledCorrectly() {
        String oracleSql =
            "FUNCTION test_string RETURN NUMBER IS\n" +
            "  TYPE message_t IS RECORD (text VARCHAR2(200));\n" +
            "  v_msg message_t;\n" +
            "BEGIN\n" +
            "  v_msg.text := 'Hello World';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 11: RECORD with String Field ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // String value should be properly wrapped
        assertTrue(normalized.contains("to_jsonb") && normalized.contains("Hello World"),
                   "String value should use to_jsonb");
    }

    // ========== TEST 12: Multiple Nested Assignments in Sequence ==========

    @Test
    void multipleNestedAssignments_allTransformCorrectly() {
        String oracleSql =
            "FUNCTION test_multiple_nested RETURN NUMBER IS\n" +
            "  TYPE address_t IS RECORD (street VARCHAR2(100), city VARCHAR2(50), zipcode VARCHAR2(10));\n" +
            "  TYPE employee_t IS RECORD (empno NUMBER, ename VARCHAR2(50), address address_t);\n" +
            "  v_emp employee_t;\n" +
            "BEGIN\n" +
            "  v_emp.empno := 100;\n" +
            "  v_emp.ename := 'John';\n" +
            "  v_emp.address.street := '123 Main';\n" +
            "  v_emp.address.city := 'Boston';\n" +
            "  v_emp.address.zipcode := '02101';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 12: Multiple Nested Assignments ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("============================================\n");

        // All five assignments should use jsonb_set
        long jsonbSetCount = result.split("jsonb_set", -1).length - 1;
        assertTrue(jsonbSetCount >= 5, "Should have at least 5 jsonb_set calls (found: " + jsonbSetCount + ")");

        // Check for both simple and nested paths
        assertTrue(normalized.contains("empno"),
                   "Simple field assignment should have empno");
        assertTrue(normalized.contains("address") && normalized.contains("city"),
                   "Nested field assignment should have address and city");
    }

    // ========== TEST 13: RECORD Type Names are Case Insensitive ==========

    @Test
    void recordTypeNames_caseInsensitive() {
        String oracleSql =
            "FUNCTION test_case_insensitive RETURN NUMBER IS\n" +
            "  TYPE Config_T IS RECORD (setting VARCHAR2(50));\n" +
            "  v_cfg Config_T;\n" +
            "BEGIN\n" +
            "  v_cfg.setting := 'value';\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 13: Case Insensitive Type Names ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("============================================\n");

        // Mixed case type names should still transform correctly
        assertTrue(result.contains("v_cfg jsonb"), "Variable should be jsonb regardless of type name case");
        assertTrue(normalized.contains("jsonb_set") && normalized.contains("v_cfg"),
                   "Field assignment should work with mixed case type name");
    }

    // ========== TEST 14: RECORD with Numeric Expression Assignment ==========

    @Test
    void recordWithNumericExpression_expressionPreserved() {
        String oracleSql =
            "FUNCTION test_expression RETURN NUMBER IS\n" +
            "  TYPE calc_t IS RECORD (result NUMBER);\n" +
            "  v_calc calc_t;\n" +
            "BEGIN\n" +
            "  v_calc.result := 10 + 20 * 2;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 14: RECORD with Numeric Expression ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("===============================================\n");

        // Expression should be wrapped in to_jsonb()
        assertTrue(normalized.contains("to_jsonb") && normalized.contains("10") &&
                   normalized.contains("20") && normalized.contains("2"),
                   "Arithmetic expression should be wrapped in to_jsonb()");
    }

    // ========== TEST 15: Complete Function with RECORD (End-to-End) ==========

    @Test
    void completeFunction_withRecord_allElementsTransform() {
        String oracleSql =
            "FUNCTION calculate_salary_range(p_dept_id NUMBER) RETURN NUMBER IS\n" +
            "  TYPE salary_range_t IS RECORD (\n" +
            "    min_sal NUMBER,\n" +
            "    max_sal NUMBER,\n" +
            "    currency VARCHAR2(10)\n" +
            "  );\n" +
            "  v_range salary_range_t;\n" +
            "BEGIN\n" +
            "  v_range.min_sal := 50000;\n" +
            "  v_range.max_sal := 150000;\n" +
            "  v_range.currency := 'USD';\n" +
            "  RETURN 100000;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 15: Complete Function with RECORD ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("==============================================\n");

        // Verify complete function structure
        assertTrue(result.contains("CREATE OR REPLACE FUNCTION"), "Should have CREATE OR REPLACE FUNCTION");
        assertTrue(result.contains("hr.calculate_salary_range"), "Should have qualified function name");
        assertTrue(result.contains("p_dept_id numeric"), "Parameter should be converted to numeric");
        assertTrue(result.contains("RETURNS numeric"), "Should have RETURNS numeric");
        assertTrue(result.contains("LANGUAGE plpgsql"), "Should have LANGUAGE plpgsql");
        assertTrue(result.contains("DECLARE"), "Should have DECLARE section");
        assertTrue(result.contains("v_range jsonb"), "RECORD variable should be jsonb");
        assertTrue(result.contains("'{}'::jsonb"), "Should have jsonb initialization");

        // Verify all three field assignments
        long jsonbSetCount = result.split("jsonb_set", -1).length - 1;
        assertTrue(jsonbSetCount >= 3, "Should have 3 jsonb_set calls for v_range (found: " + jsonbSetCount + ")");

        // Verify RETURN statement preserved
        assertTrue(result.contains("RETURN 100000"), "RETURN statement should be preserved");
    }

    // ========== TEST 16: RECORD Field Assignment with NULL Value ==========

    @Test
    void recordFieldAssignmentWithNull_handlesNullCorrectly() {
        String oracleSql =
            "FUNCTION test_null RETURN NUMBER IS\n" +
            "  TYPE data_t IS RECORD (value NUMBER);\n" +
            "  v_data data_t;\n" +
            "BEGIN\n" +
            "  v_data.value := NULL;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ").toUpperCase();

        System.out.println("\n=== TEST 16: RECORD Field with NULL ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=======================================\n");

        // NULL should be wrapped in to_jsonb()
        assertTrue(normalized.contains("TO_JSONB") && normalized.contains("NULL"),
                   "NULL value should be wrapped in to_jsonb()");
    }

    // ========== TEST 17: RECORD with CONSTANT Field Values ==========

    @Test
    void recordWithConstant_constantUsedInAssignment() {
        String oracleSql =
            "FUNCTION test_constant RETURN NUMBER IS\n" +
            "  TYPE config_t IS RECORD (timeout NUMBER);\n" +
            "  v_config config_t;\n" +
            "  c_default_timeout CONSTANT NUMBER := 30;\n" +
            "BEGIN\n" +
            "  v_config.timeout := c_default_timeout;\n" +
            "  RETURN 0;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        System.out.println("\n=== TEST 17: RECORD with CONSTANT ===");
        System.out.println("ORACLE:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL:");
        System.out.println(result);
        System.out.println("=====================================\n");

        // Constant reference should be wrapped in to_jsonb()
        assertTrue(normalized.contains("to_jsonb") && normalized.contains("c_default_timeout"),
                   "Constant reference should be wrapped in to_jsonb()");
    }
}
