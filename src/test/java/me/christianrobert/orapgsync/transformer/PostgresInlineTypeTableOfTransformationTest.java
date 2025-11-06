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
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TABLE OF type transformation (Phase 1C).
 *
 * <p>Tests collection type transformation including:
 * <ul>
 *   <li>TYPE declarations (parsing and registration)</li>
 *   <li>Variable declarations with jsonb type</li>
 *   <li>Collection constructor calls → JSON array literals</li>
 *   <li>Automatic initialization with empty arrays</li>
 * </ul>
 *
 * <p><strong>Phase 1C Scope:</strong></p>
 * <ul>
 *   <li>✅ Collection constructor transformation</li>
 *   <li>📋 Array element access (deferred to comprehensive tests after basic constructor tests pass)</li>
 * </ul>
 */
public class PostgresInlineTypeTableOfTransformationTest {

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
            new HashMap<>()  // synonyms
        );
    }

    /**
     * Helper method to transform Oracle function body to PostgreSQL.
     *
     * @param oracleFunctionBody Oracle function body (without CREATE FUNCTION wrapper)
     * @return PostgreSQL function body
     */
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

    // ========================================================================
    // TEST GROUP 1: Type Declaration and Registration
    // ========================================================================

    @Test
    void simpleTableOf_numberType_declaresAndRegisters() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
            BEGIN
              RETURN 0;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Type declaration should be commented out
        assertTrue(normalized.contains("-- TYPE num_list_t IS TABLE OF"),
            "TYPE declaration should be commented");

        // Variable should be jsonb with array initialization
        assertTrue(normalized.contains("v_nums jsonb := '[]'::jsonb"),
            "Variable should be jsonb with empty array initialization");
    }

    @Test
    void tableOf_varcharType_declaresCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE string_list_t IS TABLE OF VARCHAR2(100);
              v_strings string_list_t;
            BEGIN
              RETURN 0;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        assertTrue(normalized.contains("-- TYPE string_list_t IS TABLE OF"),
            "TYPE declaration should be commented");
        assertTrue(normalized.contains("v_strings jsonb := '[]'::jsonb"),
            "VARCHAR2 collection should also use jsonb array");
    }

    @Test
    void tableOf_dateType_declaresCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE date_list_t IS TABLE OF DATE;
              v_dates date_list_t;
            BEGIN
              RETURN 0;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        assertTrue(normalized.contains("-- TYPE date_list_t IS TABLE OF"),
            "TYPE declaration should be commented");
        assertTrue(normalized.contains("v_dates jsonb := '[]'::jsonb"),
            "DATE collection should use jsonb array");
    }

    // ========================================================================
    // TEST GROUP 2: Collection Constructor Transformation
    // ========================================================================

    @Test
    void collectionConstructor_numericElements_transformsToJsonArray() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
            BEGIN
              RETURN 0;
              v_nums := num_list_t(10, 20, 30);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Constructor should transform to JSON array
        assertTrue(normalized.contains("v_nums := '[ 10 , 20 , 30 ]'::jsonb") ||
                   normalized.contains("v_nums := '[10,20,30]'::jsonb") ||
                   normalized.contains("v_nums := '[10 , 20 , 30]'::jsonb"),
            "Collection constructor should transform to JSON array literal. Got: " + normalized);
    }

    @Test
    void collectionConstructor_stringElements_transformsWithQuotes() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE string_list_t IS TABLE OF VARCHAR2(50);
              v_codes string_list_t;
            BEGIN
              RETURN 0;
              v_codes := string_list_t('A001', 'B002', 'C003');
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // String elements should have JSON double quotes
        assertTrue(normalized.contains("\"A001\"") &&
                   normalized.contains("\"B002\"") &&
                   normalized.contains("\"C003\""),
            "String elements should be quoted in JSON array. Got: " + normalized);
        assertTrue(normalized.contains("'::jsonb"),
            "Constructor should have jsonb cast");
    }

    @Test
    void collectionConstructor_emptyList_transformsToEmptyArray() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
            BEGIN
              RETURN 0;
              v_nums := num_list_t();
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Empty constructor should produce empty JSON array
        assertTrue(normalized.contains("v_nums := '[]'::jsonb"),
            "Empty constructor should produce empty JSON array. Got: " + normalized);
    }

    @Test
    void collectionConstructor_singleElement_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
            BEGIN
              RETURN 0;
              v_nums := num_list_t(42);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Single element should still be an array
        assertTrue(normalized.contains("'[ 42 ]'::jsonb") ||
                   normalized.contains("'[42]'::jsonb"),
            "Single element constructor should produce JSON array. Got: " + normalized);
    }

    @Test
    void collectionConstructor_mixedExpressions_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
              v_base NUMBER := 100;
            BEGIN
              RETURN 0;
              v_nums := num_list_t(v_base, v_base * 2, v_base + 50);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Expressions should be included in JSON array
        assertTrue(normalized.contains("v_base") &&
                   normalized.contains("'::jsonb"),
            "Constructor with expressions should transform to JSON array. Got: " + normalized);
    }

    // ========================================================================
    // TEST GROUP 3: Multiple Collections in One Function
    // ========================================================================

    @Test
    void multipleCollections_differentTypes_transformIndependently() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              TYPE string_list_t IS TABLE OF VARCHAR2(100);
              v_nums num_list_t;
              v_strings string_list_t;
            BEGIN
              RETURN 0;
              v_nums := num_list_t(1, 2, 3);
              v_strings := string_list_t('alpha', 'beta');
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Both collections should use jsonb
        assertTrue(normalized.contains("v_nums jsonb"),
            "Numeric collection should be jsonb");
        assertTrue(normalized.contains("v_strings jsonb"),
            "String collection should be jsonb");

        // Both constructors should transform
        assertTrue(normalized.contains("1") && normalized.contains("2") && normalized.contains("3"),
            "Numeric constructor elements should be present");
        assertTrue(normalized.contains("\"alpha\"") && normalized.contains("\"beta\""),
            "String constructor elements should be present");
    }

    // ========================================================================
    // TEST GROUP 4: Collection Integration with Control Flow
    // ========================================================================

    @Test
    void collectionInIfStatement_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
              v_flag NUMBER := 1;
            BEGIN
              RETURN 0;
              IF v_flag = 1 THEN
                v_nums := num_list_t(10, 20, 30);
              ELSE
                v_nums := num_list_t(40, 50, 60);
              END IF;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Both constructors should transform
        assertTrue(normalized.contains("10") && normalized.contains("20") && normalized.contains("30"),
            "First constructor should transform");
        assertTrue(normalized.contains("40") && normalized.contains("50") && normalized.contains("60"),
            "Second constructor should transform");
        assertTrue(normalized.contains("IF") && normalized.contains("THEN") && normalized.contains("ELSE"),
            "IF statement structure should be preserved");
    }

    @Test
    void collectionInLoop_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
              v_i NUMBER;
            BEGIN
              RETURN 0;
              FOR v_i IN 1..3 LOOP
                v_nums := num_list_t(v_i * 10);
              END LOOP;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Constructor with expression should transform
        assertTrue(normalized.contains("v_i * 10") || normalized.contains("v_i*10"),
            "Constructor expression should be preserved");
        assertTrue(normalized.contains("FOR") && normalized.contains("LOOP"),
            "LOOP structure should be preserved");
    }

    // ========================================================================
    // TEST GROUP 5: Nested Collection Usage (Complex Scenarios)
    // ========================================================================

    @Test
    void collectionWithNull_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
            BEGIN
              RETURN 0;
              v_nums := num_list_t(10, NULL, 30);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // NULL should be included in JSON array
        assertTrue(normalized.contains("10") && normalized.contains("NULL") && normalized.contains("30"),
            "Constructor with NULL should transform correctly");
    }

    // ========================================================================
    // TEST GROUP 6: Edge Cases and Error Conditions
    // ========================================================================

    @Test
    void collectionVariable_noInitialization_hasAutomaticInit() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t;
            BEGIN
              RETURN 0;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Variable without explicit initialization should get automatic empty array
        assertTrue(normalized.contains("v_nums jsonb := '[]'::jsonb"),
            "Collection variable should have automatic empty array initialization");
    }

    @Test
    void collectionVariable_explicitInit_overridesAutomatic() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(100, 200);
            BEGIN
              RETURN 0;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Explicit initialization should override automatic
        assertTrue(normalized.contains("v_nums jsonb := ") &&
                   (normalized.contains("100") && normalized.contains("200")),
            "Explicit initialization should override automatic initialization");
    }

    @Test
    void caseInsensitive_typeNames_transformCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE Num_List_T IS TABLE OF NUMBER;
              v_nums Num_List_T;
            BEGIN
              RETURN 0;
              v_nums := Num_List_T(10, 20);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Case variations should still work
        assertTrue(normalized.contains("v_nums jsonb"),
            "Case-insensitive type name should work for variable declaration");
        assertTrue(normalized.contains("10") && normalized.contains("20"),
            "Case-insensitive constructor should transform");
    }

    // ========================================================================
    // TEST GROUP 7: Real-World Scenarios
    // ========================================================================

    @Test
    void realWorldScenario_departmentList_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_collection RETURN NUMBER IS
              TYPE dept_list_t IS TABLE OF VARCHAR2(50);
              v_depts dept_list_t;
              v_default_dept VARCHAR2(50) := 'Engineering';
            BEGIN
              RETURN 0;
              v_depts := dept_list_t('Engineering', 'Sales', 'Marketing');

              IF v_default_dept = 'Engineering' THEN
                v_depts := dept_list_t('Engineering', 'R&D');
              END IF;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Variable declaration
        assertTrue(normalized.contains("v_depts jsonb"),
            "Department list should be jsonb");

        // First constructor
        assertTrue(normalized.contains("\"Engineering\"") &&
                   normalized.contains("\"Sales\"") &&
                   normalized.contains("\"Marketing\""),
            "First constructor should have all departments");

        // Second constructor
        assertTrue(normalized.contains("\"R&D\""),
            "Second constructor should have R&D department");
    }
}
