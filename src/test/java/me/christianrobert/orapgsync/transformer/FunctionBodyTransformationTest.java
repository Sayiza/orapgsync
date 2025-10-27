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
 * Integration tests for PL/SQL function body transformation.
 *
 * <p>These tests verify that Oracle PL/SQL function bodies are correctly
 * transformed to PostgreSQL PL/pgSQL syntax.</p>
 *
 * <p>Tests start with the simplest possible functions and gradually increase complexity.</p>
 */
class FunctionBodyTransformationTest {

    private AntlrParser parser;
    private PostgresCodeBuilder builder;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up transformation context with schema "hr"
        TransformationIndices indices = new TransformationIndices(
            new HashMap<>(), // tableColumns
            new HashMap<>(), // typeMethods
            new HashSet<>(), // packageFunctions
            new HashMap<>()  // synonyms
        );
        TransformationContext context = new TransformationContext(
            "hr",
            indices,
            new SimpleTypeEvaluator("hr", indices)
        );
        builder = new PostgresCodeBuilder(context);
    }

    private String transform(String oracleFunctionBody) {
        ParseResult parseResult = parser.parseFunctionBody(oracleFunctionBody);
        if (parseResult.hasErrors()) {
            fail("Parse failed: " + parseResult.getErrors());
        }
        return builder.visit(parseResult.getTree());
    }

    // ========== SIMPLEST POSSIBLE FUNCTION ==========

    @Test
    void simplestFunction_returnNull() {
        // The absolute simplest function: just return NULL
        // No declarations, no complex logic, just one RETURN statement
        String oracleSql =
            "FUNCTION get_test RETURN NUMBER IS\n" +
            "BEGIN\n" +
            "  RETURN NULL;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.trim().replaceAll("\\s+", " ");

        // Debug output
        System.out.println("\n=== TEST: simplestFunction_returnNull ===");
        System.out.println("ORACLE FUNCTION:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL FUNCTION BODY:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Should have BEGIN...END block
        assertTrue(result.contains("BEGIN"), "Should have BEGIN");
        assertTrue(result.contains("END"), "Should have END");

        // Should have RETURN NULL
        assertTrue(normalized.contains("RETURN NULL"), "Should have RETURN NULL");

        // Should NOT have the function signature (that's handled by TransformationService)
        assertFalse(result.contains("FUNCTION"), "Should not include FUNCTION keyword");
        assertFalse(result.contains("RETURN NUMBER"), "Should not include RETURN type");

        // Should be valid PL/pgSQL body structure
        assertFalse(result.isEmpty(), "Should generate non-empty result");
    }

    // ========== RETURN LITERAL VALUE ==========

    @Test
    void simpleFunction_returnLiteral() {
        // Return a literal number
        String oracleSql =
            "FUNCTION get_constant RETURN NUMBER IS\n" +
            "BEGIN\n" +
            "  RETURN 42;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.trim().replaceAll("\\s+", " ");

        // Debug output
        System.out.println("\n=== TEST: simpleFunction_returnLiteral ===");
        System.out.println("ORACLE FUNCTION:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL FUNCTION BODY:");
        System.out.println(result);
        System.out.println("==========================================\n");

        // Should have RETURN 42
        assertTrue(normalized.contains("RETURN 42"), "Should have RETURN 42");

        // Should have BEGIN...END structure
        assertTrue(result.contains("BEGIN"), "Should have BEGIN");
        assertTrue(result.contains("END"), "Should have END");
    }

    // ========== RETURN STRING LITERAL ==========

    @Test
    void simpleFunction_returnString() {
        // Return a string literal
        String oracleSql =
            "FUNCTION get_greeting RETURN VARCHAR2 IS\n" +
            "BEGIN\n" +
            "  RETURN 'Hello World';\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.trim().replaceAll("\\s+", " ");

        // Debug output
        System.out.println("\n=== TEST: simpleFunction_returnString ===");
        System.out.println("ORACLE FUNCTION:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL FUNCTION BODY:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Should have RETURN 'Hello World'
        assertTrue(normalized.contains("RETURN 'Hello World'") ||
                   normalized.contains("RETURN \"Hello World\""),
                   "Should have RETURN with string literal");

        // Should have BEGIN...END structure
        assertTrue(result.contains("BEGIN"), "Should have BEGIN");
        assertTrue(result.contains("END"), "Should have END");
    }

    // ========== RETURN EXPRESSION ==========

    @Test
    void simpleFunction_returnExpression() {
        // Return a simple arithmetic expression
        String oracleSql =
            "FUNCTION calculate RETURN NUMBER IS\n" +
            "BEGIN\n" +
            "  RETURN 2 + 2;\n" +
            "END;";

        String result = transform(oracleSql);
        String normalized = result.trim().replaceAll("\\s+", " ");

        // Debug output
        System.out.println("\n=== TEST: simpleFunction_returnExpression ===");
        System.out.println("ORACLE FUNCTION:");
        System.out.println(oracleSql);
        System.out.println("\nPOSTGRESQL FUNCTION BODY:");
        System.out.println(result);
        System.out.println("============================================\n");

        // Should have RETURN with arithmetic expression
        assertTrue(normalized.contains("RETURN 2 + 2"), "Should have RETURN 2 + 2");

        // Should have BEGIN...END structure
        assertTrue(result.contains("BEGIN"), "Should have BEGIN");
        assertTrue(result.contains("END"), "Should have END");
    }
}
