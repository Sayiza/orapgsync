package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageHelperGenerator;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for package variable transformation.
 *
 * <p>Tests the COMPLETE pipeline:
 * <ol>
 *   <li>Extract package context from Oracle package spec source (simulates ALL_SOURCE query)</li>
 *   <li>Generate helper functions (initialize, getters, setters)</li>
 *   <li>Execute helpers in PostgreSQL</li>
 *   <li>Transform package functions that use variables</li>
 *   <li>Execute transformed functions in PostgreSQL</li>
 *   <li>Verify package variable state works correctly</li>
 * </ol>
 *
 * <p>This validates the full transformation architecture that was failing in production,
 * ensuring package variables are properly extracted, transformed, and function correctly.
 */
public class PostgresPackageVariableIntegrationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    void setupSchema() throws SQLException {
        // Create test schema
        executeUpdate("CREATE SCHEMA hr");
    }

    /**
     * Test 1: Simple integer package variable with getter and setter.
     *
     * <p>Validates:
     * <ul>
     *   <li>Package spec parsing extracts variables correctly</li>
     *   <li>Helper functions generated correctly</li>
     *   <li>Helper functions execute in PostgreSQL</li>
     *   <li>Package function transformation includes initialization</li>
     *   <li>Getter transformation (reading variable)</li>
     *   <li>Setter transformation (writing variable)</li>
     *   <li>Session-level state persistence</li>
     * </ul>
     */
    @Test
    void simpleIntegerVariable_getterAndSetter() throws SQLException {
        // STEP 1: Simulate Oracle package spec from ALL_SOURCE
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.counter_pkg AS
              g_counter INTEGER := 0;

              FUNCTION get_counter RETURN INTEGER;
              PROCEDURE increment_counter;
              PROCEDURE reset_counter;
            END counter_pkg;
            """;

        // STEP 2: Extract package context (what the job would do)
        System.out.println("\n=== STEP 2: Extracting Package Context ===");
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext(
            "hr", "counter_pkg", oraclePackageSpec
        );

        assertNotNull(packageContext, "Package context should be extracted");
        assertEquals(1, packageContext.getVariables().size(), "Should extract 1 variable");
        assertTrue(packageContext.hasVariable("g_counter"), "Should have g_counter variable");

        System.out.println("Extracted variables: " + packageContext.getVariables().keySet());

        // STEP 3: Generate helper functions
        System.out.println("\n=== STEP 3: Generating Helper Functions ===");
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);

        System.out.println("Generated " + helperSqls.size() + " helper functions");
        for (int i = 0; i < helperSqls.size(); i++) {
            System.out.println("\n=== Helper Function " + (i + 1) + " ===");
            System.out.println(helperSqls.get(i));
        }

        // STEP 4: Execute helpers in PostgreSQL
        System.out.println("\n=== STEP 4: Creating Helper Functions in PostgreSQL ===");
        for (String helperSql : helperSqls) {
            executeUpdate(helperSql);
        }

        // STEP 5: Transform package functions that use variables
        System.out.println("\n=== STEP 5: Transforming Package Functions ===");

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.counter_pkg", packageContext);

        // Transform get_counter function
        String getCounterOracle = """
            FUNCTION get_counter RETURN INTEGER IS
            BEGIN
              RETURN g_counter;
            END;
            """;

        TransformationResult getCounterResult = transformationService.transformFunction(
            getCounterOracle,
            "hr",
            indices,
            packageContextCache,
            "get_counter",
            "counter_pkg"
        );

        assertTrue(getCounterResult.isSuccess(),
            "get_counter transformation should succeed: " + getCounterResult.getErrorMessage());

        System.out.println("=== Transformed get_counter ===");
        System.out.println(getCounterResult.getPostgresSql());
        System.out.println("================================");

        // Verify transformation includes initialization call
        assertTrue(getCounterResult.getPostgresSql().contains("counter_pkg__initialize()"),
            "Should inject initialization call");

        // Verify transformation uses getter
        assertTrue(getCounterResult.getPostgresSql().contains("counter_pkg__get_g_counter()"),
            "Should transform variable reference to getter call");

        // Transform increment_counter procedure
        String incrementOracle = """
            PROCEDURE increment_counter IS
            BEGIN
              g_counter := g_counter + 1;
            END;
            """;

        TransformationResult incrementResult = transformationService.transformProcedure(
            incrementOracle,
            "hr",
            indices,
            packageContextCache,
            "increment_counter",
            "counter_pkg"
        );

        assertTrue(incrementResult.isSuccess(),
            "increment_counter transformation should succeed: " + incrementResult.getErrorMessage());

        System.out.println("=== Transformed increment_counter ===");
        System.out.println(incrementResult.getPostgresSql());
        System.out.println("=====================================");

        // Verify transformation uses setter and getter
        assertTrue(incrementResult.getPostgresSql().contains("counter_pkg__set_g_counter"),
            "Should transform assignment to setter call");
        assertTrue(incrementResult.getPostgresSql().contains("counter_pkg__get_g_counter()"),
            "Should transform RHS reference to getter call");

        // Transform reset_counter procedure
        String resetOracle = """
            PROCEDURE reset_counter IS
            BEGIN
              g_counter := 0;
            END;
            """;

        TransformationResult resetResult = transformationService.transformProcedure(
            resetOracle,
            "hr",
            indices,
            packageContextCache,
            "reset_counter",
            "counter_pkg"
        );

        assertTrue(resetResult.isSuccess(),
            "reset_counter transformation should succeed: " + resetResult.getErrorMessage());

        System.out.println("=== Transformed reset_counter ===");
        System.out.println(resetResult.getPostgresSql());
        System.out.println("==================================");

        // STEP 6: Execute transformed functions in PostgreSQL
        System.out.println("\n=== STEP 6: Creating Transformed Functions in PostgreSQL ===");
        executeUpdate(getCounterResult.getPostgresSql());
        executeUpdate(incrementResult.getPostgresSql());
        executeUpdate(resetResult.getPostgresSql());

        // STEP 7: Verify package variable behavior
        System.out.println("\n=== STEP 7: Testing Package Variable Behavior ===");

        // Initial value should be 0
        List<Map<String, Object>> initialResult = executeQuery(
            "SELECT hr.counter_pkg__get_counter() AS value"
        );
        assertEquals(0, ((Number) initialResult.get(0).get("value")).intValue(),
            "Initial counter value should be 0");
        System.out.println("✓ Initial value: 0");

        // Increment once
        executeUpdate("SELECT hr.counter_pkg__increment_counter()");
        List<Map<String, Object>> afterInc1 = executeQuery(
            "SELECT hr.counter_pkg__get_counter() AS value"
        );
        assertEquals(1, ((Number) afterInc1.get(0).get("value")).intValue(),
            "Counter should be 1 after one increment");
        System.out.println("✓ After increment: 1");

        // Increment again
        executeUpdate("SELECT hr.counter_pkg__increment_counter()");
        List<Map<String, Object>> afterInc2 = executeQuery(
            "SELECT hr.counter_pkg__get_counter() AS value"
        );
        assertEquals(2, ((Number) afterInc2.get(0).get("value")).intValue(),
            "Counter should be 2 after two increments");
        System.out.println("✓ After second increment: 2");

        // Reset
        executeUpdate("SELECT hr.counter_pkg__reset_counter()");
        List<Map<String, Object>> afterReset = executeQuery(
            "SELECT hr.counter_pkg__get_counter() AS value"
        );
        assertEquals(0, ((Number) afterReset.get(0).get("value")).intValue(),
            "Counter should be 0 after reset");
        System.out.println("✓ After reset: 0");

        System.out.println("\n✅ ALL PACKAGE VARIABLE TESTS PASSED!");
    }

    /**
     * Test 2: Multiple package variables with different types.
     *
     * <p>Validates:
     * <ul>
     *   <li>Multiple variable extraction</li>
     *   <li>Different data types (INTEGER, VARCHAR2, NUMBER)</li>
     *   <li>CONSTANT variables</li>
     *   <li>Variables with default values</li>
     *   <li>Multiple variables in single function</li>
     * </ul>
     */
    @Test
    void multipleVariables_differentTypes() throws SQLException {
        // Oracle package spec with multiple variables
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.config_pkg AS
              g_counter INTEGER := 0;
              g_status VARCHAR2(20) := 'ACTIVE';
              c_max_retries CONSTANT NUMBER := 3;
              g_retry_count NUMBER := 0;

              FUNCTION get_config RETURN VARCHAR2;
              PROCEDURE update_status(p_new_status VARCHAR2);
            END config_pkg;
            """;

        // Extract package context
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext(
            "hr", "config_pkg", oraclePackageSpec
        );

        assertEquals(4, packageContext.getVariables().size(), "Should extract 4 variables");
        assertTrue(packageContext.hasVariable("g_counter"));
        assertTrue(packageContext.hasVariable("g_status"));
        assertTrue(packageContext.hasVariable("c_max_retries"));
        assertTrue(packageContext.hasVariable("g_retry_count"));

        // Generate and execute helpers
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        for (String helperSql : helperSqls) {
            executeUpdate(helperSql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.config_pkg", packageContext);

        // Transform function that uses multiple variables
        String getConfigOracle = """
            FUNCTION get_config RETURN VARCHAR2 IS
              v_result VARCHAR2(200);
            BEGIN
              v_result := 'Status: ' || g_status || ', Counter: ' || g_counter;
              RETURN v_result;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(
            getConfigOracle,
            "hr",
            indices,
            packageContextCache,
            "get_config",
            "config_pkg"
        );

        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed get_config ===");
        System.out.println(result.getPostgresSql());
        System.out.println("===============================");

        // Verify transformation uses getters for both variables
        assertTrue(result.getPostgresSql().contains("config_pkg__get_g_status()"),
            "Should use getter for g_status");
        assertTrue(result.getPostgresSql().contains("config_pkg__get_g_counter()"),
            "Should use getter for g_counter");

        // Execute function
        executeUpdate(result.getPostgresSql());

        // Verify it works
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.config_pkg__get_config() AS config"
        );
        String config = (String) rows.get(0).get("config");
        assertTrue(config.contains("Status: ACTIVE"), "Should include status");
        assertTrue(config.contains("Counter: 0"), "Should include counter");

        System.out.println("✓ Config string: " + config);
        System.out.println("\n✅ MULTIPLE VARIABLES TEST PASSED!");
    }

    /**
     * Test 3: Package variable assignment with complex expression.
     *
     * <p>Validates:
     * <ul>
     *   <li>Complex RHS expressions with multiple variable references</li>
     *   <li>Setter calls with computed values</li>
     *   <li>Mixed variable access (read and write in same statement)</li>
     * </ul>
     */
    @Test
    void complexExpression_multipleVariableReferences() throws SQLException {
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.calc_pkg AS
              g_base NUMBER := 100;
              g_bonus NUMBER := 10;
              g_total NUMBER := 0;

              PROCEDURE calculate_total;
            END calc_pkg;
            """;

        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext(
            "hr", "calc_pkg", oraclePackageSpec
        );

        // Generate and execute helpers
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        for (String helperSql : helperSqls) {
            executeUpdate(helperSql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.calc_pkg", packageContext);

        // Transform procedure with complex expression
        String calculateOracle = """
            PROCEDURE calculate_total IS
            BEGIN
              g_total := g_base + g_bonus * 2;
            END;
            """;

        TransformationResult result = transformationService.transformProcedure(
            calculateOracle,
            "hr",
            indices,
            packageContextCache,
            "calculate_total",
            "calc_pkg"
        );

        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed calculate_total ===");
        System.out.println(result.getPostgresSql());
        System.out.println("====================================");

        // Verify transformation
        assertTrue(result.getPostgresSql().contains("calc_pkg__set_g_total"),
            "Should use setter for g_total");
        assertTrue(result.getPostgresSql().contains("calc_pkg__get_g_base()"),
            "Should use getter for g_base in RHS");
        assertTrue(result.getPostgresSql().contains("calc_pkg__get_g_bonus()"),
            "Should use getter for g_bonus in RHS");

        // Execute
        executeUpdate(result.getPostgresSql());

        // Call and verify
        executeUpdate("SELECT hr.calc_pkg__calculate_total()");
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.calc_pkg__get_g_total() AS total"
        );

        // g_base (100) + g_bonus (10) * 2 = 100 + 20 = 120
        assertEquals(120, ((Number) rows.get(0).get("total")).intValue(),
            "Total should be base + bonus * 2");

        System.out.println("✓ Calculated total: 120");
        System.out.println("\n✅ COMPLEX EXPRESSION TEST PASSED!");
    }
}
