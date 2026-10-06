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
import java.util.Set;
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
        return transform(oracleFunctionBody, indices);
    }

    private String transform(String oracleFunctionBody, TransformationIndices indices) {
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

    // ========== QUOTED PARAMETER NAMES (scope registration) ==========

    /**
     * A quoted parameter name must be registered in the variable scope under its canonical name.
     *
     * <p>Regression test for the bug fixed in type-method plan Phase 2a. Parameters were keyed by
     * {@code parameter_name().getText().toLowerCase()}, so {@code "Foo"} was stored under the
     * literal key {@code "foo"} -- quotes included. Every lookup canonicalises first
     * ({@code ParameterlessFunctionDetector} does {@code canonical(...).toLowerCase()} ->
     * {@code foo}), so that key could never be hit and the parameter was invisible to the scope.
     *
     * <p>Invisibility only becomes wrong output when something else claims the name. Here a
     * standalone no-arg function {@code hr.foo} exists, so with the parameter missing from scope
     * the detector reads the bare reference as a parameterless call and emits {@code hr.foo()} --
     * silently returning the function's value instead of the argument. Registered correctly, the
     * parameter wins, which is what Oracle does.
     */
    @Test
    void quotedParameterName_shadowsSameNamedStandaloneFunction() {
        // Metadata says hr.foo is a real no-arg callable routine -- the competing reading.
        TransformationIndices withFooFunction = new TransformationIndices(
            new HashMap<>(),                 // tableColumns
            new HashMap<>(),                 // typeMethods
            new HashSet<>(),                 // packageFunctions
            new HashMap<>(),                 // synonyms
            Collections.emptyMap(),          // typeFieldTypes
            Collections.emptySet(),          // objectTypeNames
            Set.of("hr.foo"),                // standaloneFunctions
            Set.of("hr.foo")                 // noArgCallableRoutines
        );

        String oracleSql =
            "FUNCTION get_label(\"Foo\" IN VARCHAR2) RETURN VARCHAR2 IS\n" +
            "BEGIN\n" +
            "  RETURN \"Foo\";\n" +
            "END;";

        String result = transform(oracleSql, withFooFunction);
        String normalized = result.trim().replaceAll("\\s+", " ");

        System.out.println("\n=== TEST: quotedParameterName_shadowsSameNamedStandaloneFunction ===");
        System.out.println(result);
        System.out.println("===================================================================\n");

        assertTrue(normalized.contains("RETURN foo;"),
            "quoted parameter should resolve as the scoped variable, got: " + normalized);
        assertFalse(normalized.contains("hr.foo()"),
            "parameter must shadow the same-named standalone function, got: " + normalized);
    }

    // ========== OBJECT TYPE NAMES IN THE DECLARE SECTION ==========

    /**
     * A local variable typed with a migrated object type must name the schema-qualified composite
     * type, not the bare Oracle name.
     *
     * <p>This is <b>not</b> a type-method concern, which is why it is pinned here as well as in
     * {@code TypeMethodTransformationTest}: {@code VisitVariable_declaration} is shared by every
     * routine form, so standalone and package functions emit the same unqualified name. The
     * 2026-08-27 run shows both — the function implementation job failed on {@code optionlist},
     * {@code url}, {@code cardrow}, {@code propmap} and others with
     * {@code type "X" does not exist}, alongside 184 type-method failures of the same shape.
     *
     * <p>An unqualified composite type resolves only when {@code search_path} happens to cover the
     * schema. During migration it does not, so this fails at {@code CREATE} — loudly, which is the
     * one mercy here.
     */
    @Test
    void localVariableOfObjectType_isSchemaQualified() {
        String normalized = transformWithObjectType("""
            FUNCTION describe_address RETURN VARCHAR2 IS
              l_addr address_type;
            BEGIN
              RETURN 'x';
            END;
            """);

        assertTrue(normalized.contains("l_addr hr.address_type;"),
            "a local of a migrated object type must name the composite type the DDL migration "
                + "created, got: " + normalized);
    }

    /**
     * The parameter route. {@code parameter} and {@code type_elements_parameter} are different
     * grammar rules with different context classes (type-method plan Phase 2a), so the Phase 2 fix
     * to the latter never reached this one — two routes to one construct, answering differently.
     */
    @Test
    void parameterOfObjectType_isSchemaQualified() {
        String normalized = transformWithObjectType("""
            FUNCTION street_of(p_addr address_type) RETURN VARCHAR2 IS
            BEGIN
              RETURN 'x';
            END;
            """);

        assertTrue(normalized.contains("p_addr hr.address_type"),
            "a parameter typed with a migrated object type must be schema-qualified, got: " + normalized);
    }

    /**
     * The return-type route. Its type-method counterpart already qualifies, which is what pinned
     * the declare section as the cause of the 2026-08-27 failures — this is the ordinary-function
     * equivalent, and it does not.
     */
    @Test
    void returnTypeOfObjectType_isSchemaQualified() {
        String normalized = transformWithObjectType("""
            FUNCTION make_address RETURN address_type IS
            BEGIN
              RETURN NULL;
            END;
            """);

        assertTrue(normalized.contains("RETURNS hr.address_type"),
            "a return type naming a migrated object type must be schema-qualified, got: " + normalized);
    }

    /** The cursor-parameter route — same construct again, a fourth grammar rule. */
    @Test
    void cursorParameterOfObjectType_isSchemaQualified() {
        String normalized = transformWithObjectType("""
            FUNCTION find_it RETURN VARCHAR2 IS
              CURSOR c_addr(p_addr address_type) IS SELECT 1 FROM dual;
            BEGIN
              RETURN 'x';
            END;
            """);

        assertTrue(normalized.contains("p_addr hr.address_type"),
            "a cursor parameter typed with a migrated object type must be schema-qualified, got: "
                + normalized);
    }

    /**
     * Transforms against indices holding exactly one migrated object type, {@code hr.address_type},
     * and returns the output with runs of whitespace collapsed.
     */
    private String transformWithObjectType(String oracleFunctionBody) {
        TransformationIndices withObjectType = new TransformationIndices(
            new HashMap<>(),                     // tableColumns
            new HashMap<>(),                     // typeMethods
            new HashSet<>(),                     // packageFunctions
            new HashMap<>(),                     // synonyms
            Collections.emptyMap(),              // typeFieldTypes
            Set.of("hr.address_type")            // objectTypeNames
        );

        return transform(oracleFunctionBody, withObjectType).replaceAll("\\s+", " ");
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
