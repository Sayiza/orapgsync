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
 * Unit tests for Oracle collection method transformations (Phase 1E).
 *
 * <p>Tests Oracle collection methods and their PostgreSQL equivalents:
 * <ul>
 *   <li>COUNT → jsonb_array_length(collection)</li>
 *   <li>EXISTS(i) → jsonb_typeof(collection->(i-1)) IS NOT NULL</li>
 *   <li>FIRST → 1 (constant - Oracle arrays start at 1)</li>
 *   <li>LAST → jsonb_array_length(collection)</li>
 *   <li>DELETE(i) → collection - (i-1)</li>
 * </ul>
 *
 * <p><strong>Phase 1E Scope:</strong></p>
 * <ul>
 *   <li>✅ COUNT method (no arguments)</li>
 *   <li>✅ EXISTS method (with index argument)</li>
 *   <li>✅ FIRST method (no arguments)</li>
 *   <li>✅ LAST method (no arguments)</li>
 *   <li>✅ DELETE method (with index argument)</li>
 *   <li>✅ Methods work in complex expressions (IF, assignments, loops)</li>
 *   <li>✅ 1-based → 0-based index conversion for EXISTS and DELETE</li>
 * </ul>
 */
public class PostgresInlineTypeCollectionMethodsTest {

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
    // TEST GROUP 1: COUNT Method
    // ========================================================================

    @Test
    void countMethod_simpleUsage_transformsToJsonbArrayLength() {
        String oracleSql = """
            FUNCTION test_count RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_count NUMBER;
            BEGIN
              v_count := v_nums.COUNT;
              RETURN v_count;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.COUNT → jsonb_array_length( v_nums )
        assertTrue(normalized.contains("v_count := jsonb_array_length( v_nums )"),
            "COUNT method should transform to jsonb_array_length. Got: " + normalized);
    }

    @Test
    void countMethod_inIfCondition_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_count_if RETURN VARCHAR2 IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20);
            BEGIN
              IF v_nums.COUNT > 5 THEN
                RETURN 'Many';
              ELSE
                RETURN 'Few';
              END IF;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.COUNT > 5 → jsonb_array_length( v_nums ) > 5
        assertTrue(normalized.contains("IF jsonb_array_length( v_nums ) > 5 THEN"),
            "COUNT in IF condition should work. Got: " + normalized);
    }

    @Test
    void countMethod_inLoop_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_count_loop RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_total NUMBER := 0;
            BEGIN
              FOR i IN 1..v_nums.COUNT LOOP
                v_total := v_total + i;
              END LOOP;
              RETURN v_total;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // FOR i IN 1..v_nums.COUNT → FOR i IN 1..jsonb_array_length( v_nums )
        assertTrue(normalized.contains("FOR i IN 1..jsonb_array_length( v_nums ) LOOP"),
            "COUNT in FOR loop range should work. Got: " + normalized);
    }

    // ========================================================================
    // TEST GROUP 2: EXISTS Method
    // ========================================================================

    @Test
    void existsMethod_withLiteralIndex_convertsTo0Based() {
        String oracleSql = """
            FUNCTION test_exists RETURN BOOLEAN IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              IF v_nums.EXISTS(1) THEN
                RETURN TRUE;
              END IF;
              RETURN FALSE;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.EXISTS(1) → jsonb_typeof( v_nums -> 0 ) IS NOT NULL
        // Oracle index 1 → PostgreSQL index 0
        assertTrue(normalized.contains("IF jsonb_typeof( v_nums -> 0 ) IS NOT NULL THEN"),
            "EXISTS(1) should convert to 0-based index. Got: " + normalized);
    }

    @Test
    void existsMethod_withVariableIndex_appliesConversion() {
        String oracleSql = """
            FUNCTION test_exists_var RETURN BOOLEAN IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_idx NUMBER := 2;
            BEGIN
              IF v_nums.EXISTS(v_idx) THEN
                RETURN TRUE;
              END IF;
              RETURN FALSE;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.EXISTS(v_idx) → jsonb_typeof( v_nums -> ( v_idx - 1 )::int ) IS NOT NULL
        assertTrue(normalized.contains("jsonb_typeof( v_nums -> ( v_idx - 1 )::int ) IS NOT NULL"),
            "EXISTS with variable should apply index conversion with int cast. Got: " + normalized);
    }

    @Test
    void existsMethod_inWhileLoop_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_exists_while RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_idx NUMBER := 1;
              v_sum NUMBER := 0;
            BEGIN
              WHILE v_nums.EXISTS(v_idx) LOOP
                v_idx := v_idx + 1;
              END LOOP;
              RETURN v_sum;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // WHILE v_nums.EXISTS(v_idx) → WHILE jsonb_typeof(...) IS NOT NULL (with int cast)
        assertTrue(normalized.contains("WHILE jsonb_typeof( v_nums -> ( v_idx - 1 )::int ) IS NOT NULL LOOP"),
            "EXISTS in WHILE condition should work with int cast. Got: " + normalized);
    }

    // ========================================================================
    // TEST GROUP 3: FIRST Method
    // ========================================================================

    @Test
    void firstMethod_simpleUsage_returnsConstant1() {
        String oracleSql = """
            FUNCTION test_first RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              RETURN v_nums.FIRST;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.FIRST → 1 (constant - Oracle arrays always start at 1)
        assertTrue(normalized.contains("RETURN 1"),
            "FIRST method should return constant 1. Got: " + normalized);
    }

    @Test
    void firstMethod_inAssignment_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_first_assign RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_first_idx NUMBER;
            BEGIN
              v_first_idx := v_nums.FIRST;
              RETURN v_first_idx;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_first_idx := v_nums.FIRST → v_first_idx := 1
        assertTrue(normalized.contains("v_first_idx := 1"),
            "FIRST in assignment should work. Got: " + normalized);
    }

    // ========================================================================
    // TEST GROUP 4: LAST Method
    // ========================================================================

    @Test
    void lastMethod_simpleUsage_transformsToJsonbArrayLength() {
        String oracleSql = """
            FUNCTION test_last RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              RETURN v_nums.LAST;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.LAST → jsonb_array_length( v_nums )
        // For 1-based Oracle arrays, LAST = COUNT
        assertTrue(normalized.contains("RETURN jsonb_array_length( v_nums )"),
            "LAST method should transform to jsonb_array_length. Got: " + normalized);
    }

    @Test
    void lastMethod_inForLoop_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_last_loop RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_sum NUMBER := 0;
            BEGIN
              FOR i IN 1..v_nums.LAST LOOP
                v_sum := v_sum + i;
              END LOOP;
              RETURN v_sum;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // FOR i IN 1..v_nums.LAST → FOR i IN 1..jsonb_array_length( v_nums )
        assertTrue(normalized.contains("FOR i IN 1..jsonb_array_length( v_nums ) LOOP"),
            "LAST in FOR loop should work. Got: " + normalized);
    }

    // ========================================================================
    // TEST GROUP 5: DELETE Method
    // ========================================================================

    @Test
    void deleteMethod_withLiteralIndex_convertsTo0Based() {
        String oracleSql = """
            FUNCTION test_delete RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              v_nums := v_nums.DELETE(1);
              RETURN v_nums.COUNT;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.DELETE(1) → v_nums - 0
        // PostgreSQL jsonb - operator removes element by index (0-based)
        assertTrue(normalized.contains("v_nums := v_nums - 0"),
            "DELETE(1) should convert to 0-based index. Got: " + normalized);
    }

    @Test
    void deleteMethod_withVariableIndex_appliesConversion() {
        String oracleSql = """
            FUNCTION test_delete_var RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_idx NUMBER := 2;
            BEGIN
              v_nums := v_nums.DELETE(v_idx);
              RETURN v_nums.COUNT;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // v_nums.DELETE(v_idx) → v_nums - ( v_idx - 1 )
        assertTrue(normalized.contains("v_nums := v_nums - ( v_idx - 1 )"),
            "DELETE with variable should apply index conversion. Got: " + normalized);
    }

    // ========================================================================
    // TEST GROUP 6: Complex Scenarios
    // ========================================================================

    @Test
    void multipleMethods_inSameFunction_allTransformCorrectly() {
        String oracleSql = """
            FUNCTION test_multiple_methods RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_count NUMBER;
              v_first NUMBER;
              v_last NUMBER;
            BEGIN
              v_count := v_nums.COUNT;
              v_first := v_nums.FIRST;
              v_last := v_nums.LAST;

              IF v_nums.EXISTS(1) THEN
                v_nums := v_nums.DELETE(1);
              END IF;

              RETURN v_count + v_first + v_last;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // All methods should be transformed
        assertTrue(normalized.contains("v_count := jsonb_array_length( v_nums )"),
            "COUNT method should be transformed");
        assertTrue(normalized.contains("v_first := 1"),
            "FIRST method should be transformed");
        assertTrue(normalized.contains("v_last := jsonb_array_length( v_nums )"),
            "LAST method should be transformed");
        assertTrue(normalized.contains("IF jsonb_typeof( v_nums -> 0 ) IS NOT NULL THEN"),
            "EXISTS method should be transformed");
        assertTrue(normalized.contains("v_nums := v_nums - 0"),
            "DELETE method should be transformed");
    }

    @Test
    void countMethod_withVarrayType_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_varray_count RETURN NUMBER IS
              TYPE codes_t IS VARRAY(10) OF VARCHAR2(10);
              v_codes codes_t := codes_t('A', 'B', 'C');
            BEGIN
              RETURN v_codes.COUNT;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // VARRAY collections also use COUNT method
        assertTrue(normalized.contains("RETURN jsonb_array_length( v_codes )"),
            "COUNT should work with VARRAY. Got: " + normalized);
    }

    @Test
    void existsMethod_withIndexByCollection_transformsCorrectly() {
        String oracleSql = """
            FUNCTION test_indexby_exists RETURN BOOLEAN IS
              TYPE num_map_t IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
              v_map num_map_t;
            BEGIN
              v_map(1) := 100;
              v_map(5) := 500;

              IF v_map.EXISTS(5) THEN
                RETURN TRUE;
              END IF;
              RETURN FALSE;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // EXISTS works with INDEX BY collections too
        assertTrue(normalized.contains("IF jsonb_typeof( v_map -> 4 ) IS NOT NULL THEN"),
            "EXISTS should work with INDEX BY. Got: " + normalized);
    }
}
