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
 * Unit tests for PL/SQL function body transformation.
 *
 * <p>These tests verify that Oracle PL/SQL function bodies are correctly
 * transformed to complete PostgreSQL CREATE OR REPLACE FUNCTION statements.</p>
 *
 * <p>Tests start with the simplest possible functions and gradually increase complexity.</p>
 */
class FunctionBodyTransformationTest {

    private AntlrParser parser;
    private TransformationIndices indices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();

        // Set up transformation indices
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

        // Create context (only schema needed - function name/params extracted from AST)
        TransformationContext context = new TransformationContext(
            "hr",
            indices,
            new SimpleTypeEvaluator("hr", indices)
        );
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);

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
        System.out.println("\nPOSTGRESQL COMPLETE FUNCTION:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Should have complete CREATE OR REPLACE FUNCTION statement
        assertTrue(result.contains("CREATE OR REPLACE FUNCTION"), "Should have CREATE OR REPLACE FUNCTION");
        assertTrue(result.contains("hr.get_test"), "Should have qualified function name");
        assertTrue(result.contains("RETURNS numeric"), "Should have RETURNS numeric");
        assertTrue(result.contains("LANGUAGE plpgsql"), "Should have LANGUAGE plpgsql");
        assertTrue(result.contains("AS $$"), "Should have AS $$");
        assertTrue(result.contains("$$;"), "Should have $$;");

        // Should have BEGIN...END block
        assertTrue(result.contains("BEGIN"), "Should have BEGIN");
        assertTrue(result.contains("END"), "Should have END");

        // Should have RETURN NULL
        assertTrue(normalized.contains("RETURN NULL"), "Should have RETURN NULL");
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
        System.out.println("\nPOSTGRESQL COMPLETE FUNCTION:");
        System.out.println(result);
        System.out.println("==========================================\n");

        // Should have complete CREATE OR REPLACE FUNCTION statement
        assertTrue(result.contains("CREATE OR REPLACE FUNCTION"), "Should have CREATE OR REPLACE FUNCTION");
        assertTrue(result.contains("hr.get_constant"), "Should have qualified function name");
        assertTrue(result.contains("RETURNS numeric"), "Should have RETURNS numeric");

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
        System.out.println("\nPOSTGRESQL COMPLETE FUNCTION:");
        System.out.println(result);
        System.out.println("=========================================\n");

        // Should have complete CREATE OR REPLACE FUNCTION statement
        assertTrue(result.contains("CREATE OR REPLACE FUNCTION"), "Should have CREATE OR REPLACE FUNCTION");
        assertTrue(result.contains("hr.get_greeting"), "Should have qualified function name");
        assertTrue(result.contains("RETURNS text"), "Should have RETURNS text");

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
        System.out.println("\nPOSTGRESQL COMPLETE FUNCTION:");
        System.out.println(result);
        System.out.println("============================================\n");

        // Should have complete CREATE OR REPLACE FUNCTION statement
        assertTrue(result.contains("CREATE OR REPLACE FUNCTION"), "Should have CREATE OR REPLACE FUNCTION");
        assertTrue(result.contains("hr.calculate"), "Should have qualified function name");
        assertTrue(result.contains("RETURNS numeric"), "Should have RETURNS numeric");

        // Should have RETURN with arithmetic expression
        assertTrue(normalized.contains("RETURN 2 + 2"), "Should have RETURN 2 + 2");

        // Should have BEGIN...END structure
        assertTrue(result.contains("BEGIN"), "Should have BEGIN");
        assertTrue(result.contains("END"), "Should have END");
    }
}
