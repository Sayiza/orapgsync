package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for package variable getter/setter transformation.
 * Verifies that package variable references are correctly transformed
 * to getter/setter calls in PL/SQL code.
 */
class PackageVariableTransformationTest {

    private AntlrParser parser;
    private Map<String, PackageContext> packageContextCache;
    private TransformationIndices emptyIndices;
    private SimpleTypeEvaluator typeEvaluator;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        packageContextCache = new HashMap<>();

        // Set up a sample package context with variables
        PackageContext empPkgContext = new PackageContext("hr", "emp_pkg");
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "g_status", "VARCHAR2(20)", "'ACTIVE'", false
        ));
        empPkgContext.addVariable(new PackageContext.PackageVariable(
            "c_max_salary", "NUMBER", "10000", true
        ));

        packageContextCache.put("hr.emp_pkg", empPkgContext);

        // Create empty transformation indices for testing
        emptyIndices = new TransformationIndices(
            Collections.emptyMap(),  // tableColumns
            Collections.emptyMap(),  // typeMethods
            Collections.emptySet(),  // packageFunctions
            Collections.emptyMap()   // synonyms
        );

        // Create simple type evaluator for testing
        typeEvaluator = new SimpleTypeEvaluator("hr", emptyIndices);
    }

    @Test
    void transformAssignmentToPackageVariableSetter() {
        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_counter := 100;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        // Create code builder with package context
        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to PERFORM setter call
        assertTrue(postgresSql.contains("PERFORM hr.emp_pkg__set_g_counter(100)"),
            "Assignment should be transformed to setter call");
    }

    @Test
    void transformPackageVariableReferenceToGetter() {
        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
              v_result NUMBER;
            BEGIN
              v_result := emp_pkg.g_counter + 10;
              RETURN v_result;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to getter call
        assertTrue(postgresSql.contains("hr.emp_pkg__get_g_counter()"),
            "Package variable reference should be transformed to getter call");
    }

    @Test
    void transformMultipleVariableReferences() {
        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_counter := emp_pkg.g_counter + 1;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // LHS should be setter, RHS should be getter
        assertTrue(postgresSql.contains("PERFORM hr.emp_pkg__set_g_counter"),
            "LHS should be transformed to setter");
        assertTrue(postgresSql.contains("hr.emp_pkg__get_g_counter()"),
            "RHS should be transformed to getter");
    }

    @Test
    void transformStringVariableAssignment() {
        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              emp_pkg.g_status := 'INACTIVE';
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        assertTrue(postgresSql.contains("PERFORM hr.emp_pkg__set_g_status('INACTIVE')"),
            "String assignment should be transformed to setter call");
    }

    @Test
    void nonPackageVariableNotTransformed() {
        String plsql = """
            PROCEDURE test_proc AS
              v_local NUMBER := 10;
            BEGIN
              v_local := v_local + 1;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // Local variable should NOT be transformed
        assertTrue(postgresSql.contains("v_local := v_local + 1"),
            "Local variable should not be transformed");
        assertFalse(postgresSql.contains("__set_"),
            "Should not contain setter calls for local variables");
    }

    @Test
    void packageVariableInIfCondition() {
        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              IF emp_pkg.g_counter > 10 THEN
                NULL;
              END IF;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // Should transform to getter in condition
        assertTrue(postgresSql.contains("hr.emp_pkg__get_g_counter()"),
            "Package variable in IF condition should be transformed to getter");
    }

    @Test
    void initializationCallInjectedIntoFunctionBody() {
        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
            BEGIN
              RETURN emp_pkg.g_counter;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // Should inject initialization call at start of BEGIN block
        assertTrue(postgresSql.contains("PERFORM hr.emp_pkg__initialize()"),
            "Initialization call should be injected into function body");

        // Initialization should come before other statements
        int initIndex = postgresSql.indexOf("PERFORM hr.emp_pkg__initialize()");
        // Find the actual RETURN statement in the body (not RETURNS clause in signature)
        int beginIndex = postgresSql.indexOf("BEGIN");
        int returnIndex = postgresSql.indexOf("RETURN ", beginIndex);  // RETURN with space to avoid RETURNS
        assertTrue(initIndex < returnIndex && initIndex > beginIndex,
            "Initialization call should come after BEGIN and before RETURN statement");
    }

    @Test
    void noInitializationForStandaloneFunction() {
        String plsql = """
            FUNCTION test_func RETURN NUMBER AS
            BEGIN
              RETURN 42;
            END;
            """;

        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        // Don't set package name - this is a standalone function
        builder.setCurrentPackage("hr", null);

        String postgresSql = builder.visit(parseResult.getTree());

        // Should NOT inject initialization for standalone functions
        assertFalse(postgresSql.contains("__initialize()"),
            "Standalone functions should not have initialization call");
    }

    @Test
    void caseInsensitiveVariableReference() {
        String plsql = """
            PROCEDURE test_proc AS
            BEGIN
              EMP_PKG.G_COUNTER := 100;
            END;
            """;

        ParseResult parseResult = parser.parseProcedureBody(plsql);
        assertTrue(parseResult.isSuccess());

        TransformationContext context = new TransformationContext("hr", emptyIndices, typeEvaluator);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context, packageContextCache);
        builder.setCurrentPackage("hr", "emp_pkg");

        String postgresSql = builder.visit(parseResult.getTree());

        // Should still transform (case-insensitive match)
        assertTrue(postgresSql.contains("PERFORM hr.emp_pkg__set_g_counter"),
            "Case-insensitive variable reference should be transformed");
    }
}
