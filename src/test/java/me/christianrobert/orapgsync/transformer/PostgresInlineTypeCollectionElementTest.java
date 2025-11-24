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
 * Unit tests for collection element access and assignment (Phase 1C.5 + 1D).
 *
 * <p>Tests collection element operations including:
 * <ul>
 *   <li>Array element access (RHS): v_nums(i) → (v_nums->(i-1))</li>
 *   <li>Array element assignment (LHS): v_nums(i) := value → jsonb_set(...)</li>
 *   <li>Map element access (RHS): v_map('key') → (v_map->>'key')</li>
 *   <li>Map element assignment (LHS): v_map('key') := value → jsonb_set(...)</li>
 *   <li>1-based → 0-based index conversion for arrays</li>
 * </ul>
 *
 * <p><strong>Phase 1C.5 + 1D Scope:</strong></p>
 * <ul>
 *   <li>✅ Array element access with numeric literals</li>
 *   <li>✅ Array element access with variables</li>
 *   <li>✅ Array element assignment</li>
 *   <li>✅ Map element access with string literals</li>
 *   <li>✅ Map element assignment</li>
 * </ul>
 */
public class PostgresInlineTypeCollectionElementTest {

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
    // TEST GROUP 1: Array Element Access (RHS)
    // ========================================================================

    @Test
    void arrayElementAccess_numericLiteral_convertsTo0Based() {
        String oracleSql = """
            FUNCTION test_array_access RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_result NUMBER;
            BEGIN
              v_result := v_nums(1);
              RETURN v_result;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Array element access: v_nums(1) → (v_nums->>0)::numeric
        // Oracle 1-based → PostgreSQL 0-based with type casting
        assertTrue(normalized.contains("v_result := ( v_nums->>0 )::numeric"),
            "Array access should convert 1-based to 0-based with type cast: " + result);
    }

    @Test
    void arrayElementAccess_variableIndex_subtractsOne() {
        String oracleSql = """
            FUNCTION test_array_variable_index RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              i NUMBER := 2;
              v_result NUMBER;
            BEGIN
              v_result := v_nums(i);
              RETURN v_result;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Array element access with variable: v_nums(i) → (v_nums->>((i - 1)::int))::numeric
        assertTrue(normalized.contains("v_result := ( v_nums->>( i - 1 )::int )::numeric"),
            "Array access with variable should subtract 1 and cast index to int: " + result);
    }

    @Test
    void arrayElementAccess_multipleElements_eachConverted() {
        String oracleSql = """
            FUNCTION test_multiple_array_access RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_result NUMBER;
            BEGIN
              v_result := v_nums(1) + v_nums(2) + v_nums(3);
              RETURN v_result;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Multiple array accesses in expression (with type casts for arithmetic)
        assertTrue(normalized.contains("( v_nums->>0 )::numeric"),
            "First array access should be 0-based with type cast");
        assertTrue(normalized.contains("( v_nums->>1 )::numeric"),
            "Second array access should be 1-based with type cast");
        assertTrue(normalized.contains("( v_nums->>2 )::numeric"),
            "Third array access should be 2-based with type cast");
    }

    // ========================================================================
    // TEST GROUP 2: Array Element Assignment (LHS)
    // ========================================================================

    @Test
    void arrayElementAssignment_numericLiteral_usesJsonbSet() {
        String oracleSql = """
            FUNCTION test_array_assignment RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              v_nums(1) := 100;
              RETURN v_nums(1);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Array assignment: v_nums(1) := 100 → v_nums := jsonb_set(v_nums, '{0}', to_jsonb(100))
        assertTrue(normalized.contains("v_nums := jsonb_set( v_nums , '{ 0 }' , to_jsonb( 100 ) )"),
            "Array assignment should use jsonb_set with 0-based index: " + result);
    }

    @Test
    void arrayElementAssignment_variableIndex_dynamicPath() {
        String oracleSql = """
            FUNCTION test_array_assignment_variable RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
              i NUMBER := 2;
            BEGIN
              v_nums(i) := 200;
              RETURN v_nums(i);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Array assignment with variable: v_nums(i) := 200 → v_nums := jsonb_set(v_nums, '{' || (i-1) || '}', to_jsonb(200))
        assertTrue(normalized.contains("v_nums := jsonb_set( v_nums , '{ ' || ( i - 1 ) || ' }' , to_jsonb( 200 ) )"),
            "Array assignment with variable should use dynamic path: " + result);
    }

    @Test
    void arrayElementAssignment_multipleUpdates_eachTransformed() {
        String oracleSql = """
            FUNCTION test_multiple_array_updates RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              v_nums(1) := 100;
              v_nums(2) := 200;
              v_nums(3) := 300;
              RETURN v_nums(2);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Multiple array assignments
        assertTrue(normalized.contains("jsonb_set( v_nums , '{ 0 }' , to_jsonb( 100 ) )"),
            "First assignment should update index 0");
        assertTrue(normalized.contains("jsonb_set( v_nums , '{ 1 }' , to_jsonb( 200 ) )"),
            "Second assignment should update index 1");
        assertTrue(normalized.contains("jsonb_set( v_nums , '{ 2 }' , to_jsonb( 300 ) )"),
            "Third assignment should update index 2");
    }

    // ========================================================================
    // TEST GROUP 3: Map Element Access (RHS) - INDEX BY
    // ========================================================================

    @Test
    void mapElementAccess_stringKey_usesTextOperator() {
        String oracleSql = """
            FUNCTION test_map_access RETURN VARCHAR2 IS
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
              v_map dept_map_t;
              v_result VARCHAR2(100);
            BEGIN
              v_map('dept10') := 'Engineering';
              v_result := v_map('dept10');
              RETURN v_result;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Map access: v_map('dept10') → (v_map->>'dept10')
        assertTrue(normalized.contains("v_result := ( v_map ->> 'dept10' )"),
            "Map access should use ->> operator for text extraction: " + result);
    }

    @Test
    void mapElementAccess_multipleKeys_eachTransformed() {
        String oracleSql = """
            FUNCTION test_multiple_map_access RETURN VARCHAR2 IS
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
              v_map dept_map_t;
              v_result VARCHAR2(100);
            BEGIN
              v_map('dept10') := 'Engineering';
              v_map('dept20') := 'Sales';
              v_result := v_map('dept10') || ' - ' || v_map('dept20');
              RETURN v_result;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Multiple map accesses
        assertTrue(normalized.contains("( v_map ->> 'dept10' )"),
            "First map access should use dept10 key");
        assertTrue(normalized.contains("( v_map ->> 'dept20' )"),
            "Second map access should use dept20 key");
    }

    // ========================================================================
    // TEST GROUP 4: Map Element Assignment (LHS) - INDEX BY
    // ========================================================================

    @Test
    void mapElementAssignment_stringKey_usesJsonbSet() {
        String oracleSql = """
            FUNCTION test_map_assignment RETURN VARCHAR2 IS
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
              v_map dept_map_t;
            BEGIN
              v_map('dept10') := 'Engineering';
              RETURN v_map('dept10');
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Map assignment: v_map('dept10') := 'Engineering' → v_map := jsonb_set(v_map, '{dept10}', to_jsonb('Engineering'::text))
        assertTrue(normalized.contains("v_map := jsonb_set( v_map , '{ dept10 }' , to_jsonb( 'Engineering'::text ) )"),
            "Map assignment should use jsonb_set with string key: " + result);
    }

    @Test
    void mapElementAssignment_multipleKeys_eachTransformed() {
        String oracleSql = """
            FUNCTION test_multiple_map_updates RETURN NUMBER IS
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
              v_map dept_map_t;
            BEGIN
              v_map('dept10') := 'Engineering';
              v_map('dept20') := 'Sales';
              v_map('dept30') := 'Marketing';
              RETURN 0;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Multiple map assignments
        assertTrue(normalized.contains("jsonb_set( v_map , '{ dept10 }' , to_jsonb( 'Engineering'::text ) )"),
            "First map assignment should use dept10 key");
        assertTrue(normalized.contains("jsonb_set( v_map , '{ dept20 }' , to_jsonb( 'Sales'::text ) )"),
            "Second map assignment should use dept20 key");
        assertTrue(normalized.contains("jsonb_set( v_map , '{ dept30 }' , to_jsonb( 'Marketing'::text ) )"),
            "Third map assignment should use dept30 key");
    }

    // ========================================================================
    // TEST GROUP 5: Complex Scenarios
    // ========================================================================

    @Test
    void mixedCollectionOperations_arrayAndMapInSameFunction() {
        String oracleSql = """
            FUNCTION test_mixed_collections RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(50);
              v_nums num_list_t := num_list_t(10, 20, 30);
              v_map dept_map_t;
              v_result NUMBER;
            BEGIN
              v_nums(1) := 100;
              v_map('total') := 'Total: 100';
              v_result := v_nums(1);
              RETURN v_result;
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Both array and map operations should work
        assertTrue(normalized.contains("jsonb_set( v_nums , '{ 0 }' , to_jsonb( 100 ) )"),
            "Array assignment should work");
        assertTrue(normalized.contains("jsonb_set( v_map , '{ total }' , to_jsonb( 'Total: 100'::text ) )"),
            "Map assignment should work");
        assertTrue(normalized.contains("v_result := ( v_nums->>0 )::numeric"),
            "Array access should work with type cast");
    }

    @Test
    void collectionElementInConditional_accessAndAssignment() {
        String oracleSql = """
            FUNCTION test_collection_in_if RETURN NUMBER IS
              TYPE num_list_t IS TABLE OF NUMBER;
              v_nums num_list_t := num_list_t(10, 20, 30);
            BEGIN
              IF v_nums(1) > 5 THEN
                v_nums(2) := v_nums(1) * 2;
              END IF;
              RETURN v_nums(2);
            END;
            """;

        String result = transform(oracleSql);
        String normalized = result.replaceAll("\\s+", " ");

        // Array access in IF condition (with type cast for numeric comparison)
        assertTrue(normalized.contains("IF ( v_nums->>0 )::numeric > 5 THEN"),
            "Array access in condition should work with type cast");

        // Array assignment in IF body (with type cast for arithmetic)
        assertTrue(normalized.contains("jsonb_set( v_nums , '{ 1 }' , to_jsonb( ( v_nums->>0 )::numeric * 2 ) )"),
            "Array assignment in IF body should work with type cast");
    }
}
