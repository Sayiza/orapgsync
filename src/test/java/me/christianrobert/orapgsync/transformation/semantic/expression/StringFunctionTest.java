package me.christianrobert.orapgsync.transformation.semantic.expression;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.context.TransformationIndices;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StringFunction}.
 * Tests the transformation logic for Oracle string functions.
 */
class StringFunctionTest {

    private TransformationContext context;

    @BeforeEach
    void setUp() {
        // Create empty metadata indices for testing
        TransformationIndices indices = new TransformationIndices(
            new HashMap<>(),  // tableColumns
            new HashMap<>(),  // typeMethods
            Collections.emptySet(),  // packageFunctions
            new HashMap<>()   // synonyms
        );

        context = new TransformationContext("test_schema", indices);
    }

    // ========== NVL FUNCTION TESTS ==========

    @Test
    @DisplayName("NVL with simple column and literal should transform to COALESCE")
    void nvl_simpleColumnAndLiteral() {
        // Oracle: NVL(salary, 0)
        // Expected: COALESCE(salary, 0)

        SemanticNode arg1 = new Identifier("salary");
        SemanticNode arg2 = new Identifier("0");  // Using Identifier for literal for now

        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(arg1, arg2)
        );

        String result = nvlFunction.toPostgres(context);

        assertEquals("COALESCE(salary, 0)", result);
    }

    @Test
    @DisplayName("NVL with two columns should transform to COALESCE")
    void nvl_twoColumns() {
        // Oracle: NVL(commission, bonus)
        // Expected: COALESCE(commission, bonus)

        SemanticNode arg1 = new Identifier("commission");
        SemanticNode arg2 = new Identifier("bonus");

        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(arg1, arg2)
        );

        String result = nvlFunction.toPostgres(context);

        assertEquals("COALESCE(commission, bonus)", result);
    }

    @Test
    @DisplayName("NVL with qualified column names should transform correctly")
    void nvl_qualifiedColumns() {
        // Oracle: NVL(emp.salary, dept.min_salary)
        // Expected: COALESCE(emp.salary, dept.min_salary)

        SemanticNode arg1 = new Identifier("emp.salary");
        SemanticNode arg2 = new Identifier("dept.min_salary");

        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(arg1, arg2)
        );

        String result = nvlFunction.toPostgres(context);

        assertEquals("COALESCE(emp.salary, dept.min_salary)", result);
    }

    @Test
    @DisplayName("NVL with incorrect number of arguments should throw exception")
    void nvl_incorrectArgumentCount_shouldThrow() {
        // NVL requires exactly 2 arguments

        SemanticNode arg1 = new Identifier("salary");

        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(arg1)  // Only 1 argument
        );

        assertThrows(IllegalStateException.class, () -> {
            nvlFunction.toPostgres(context);
        });
    }

    @Test
    @DisplayName("NVL with three arguments should throw exception")
    void nvl_tooManyArguments_shouldThrow() {
        SemanticNode arg1 = new Identifier("salary");
        SemanticNode arg2 = new Identifier("0");
        SemanticNode arg3 = new Identifier("1000");

        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(arg1, arg2, arg3)
        );

        assertThrows(IllegalStateException.class, () -> {
            nvlFunction.toPostgres(context);
        });
    }

    // ========== UNSUPPORTED FUNCTIONS ==========

    @Test
    @DisplayName("DECODE function should throw UnsupportedOperationException")
    void decode_notYetImplemented() {
        SemanticNode arg1 = new Identifier("status");

        StringFunction decodeFunction = new StringFunction(
            StringFunction.FunctionType.DECODE,
            Arrays.asList(arg1)
        );

        assertThrows(UnsupportedOperationException.class, () -> {
            decodeFunction.toPostgres(context);
        });
    }

    @Test
    @DisplayName("SUBSTR function should throw UnsupportedOperationException")
    void substr_notYetImplemented() {
        SemanticNode arg1 = new Identifier("name");

        StringFunction substrFunction = new StringFunction(
            StringFunction.FunctionType.SUBSTR,
            Arrays.asList(arg1)
        );

        assertThrows(UnsupportedOperationException.class, () -> {
            substrFunction.toPostgres(context);
        });
    }

    // ========== CONSTRUCTOR VALIDATION ==========

    @Test
    @DisplayName("Constructor with null functionType should throw IllegalArgumentException")
    void constructor_nullFunctionType_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> {
            new StringFunction(null, Arrays.asList(new Identifier("test")));
        });
    }

    @Test
    @DisplayName("Constructor with null arguments list should throw IllegalArgumentException")
    void constructor_nullArguments_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> {
            new StringFunction(StringFunction.FunctionType.NVL, null);
        });
    }

    // ========== GETTER TESTS ==========

    @Test
    @DisplayName("getFunctionType should return correct function type")
    void getFunctionType_shouldReturnCorrectType() {
        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(new Identifier("col1"), new Identifier("col2"))
        );

        assertEquals(StringFunction.FunctionType.NVL, nvlFunction.getFunctionType());
    }

    @Test
    @DisplayName("getArguments should return correct arguments")
    void getArguments_shouldReturnCorrectArguments() {
        SemanticNode arg1 = new Identifier("col1");
        SemanticNode arg2 = new Identifier("col2");
        List<SemanticNode> args = Arrays.asList(arg1, arg2);

        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            args
        );

        assertEquals(2, nvlFunction.getArguments().size());
        assertSame(arg1, nvlFunction.getArguments().get(0));
        assertSame(arg2, nvlFunction.getArguments().get(1));
    }

    @Test
    @DisplayName("toString should contain function type and arguments")
    void toString_shouldContainFunctionDetails() {
        StringFunction nvlFunction = new StringFunction(
            StringFunction.FunctionType.NVL,
            Arrays.asList(new Identifier("col1"), new Identifier("col2"))
        );

        String result = nvlFunction.toString();

        assertTrue(result.contains("StringFunction"));
        assertTrue(result.contains("NVL"));
    }
}
