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
        // Package variables use transaction-local storage (set_config with is_local=true),
        // so we must run all verification within a single transaction to test persistence.
        // This matches web gateway behavior where each request runs in one transaction.
        System.out.println("\n=== STEP 7: Testing Package Variable Behavior (in single transaction) ===");

        connection.setAutoCommit(false);
        try {
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

            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }

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
     * Test 3: Package body variables (private variables declared in body).
     *
     * <p>Validates:
     * <ul>
     *   <li>Body variable extraction (variables declared in package body, not spec)</li>
     *   <li>Helper function generation for body variables</li>
     *   <li>Transformation of functions that use body variables</li>
     *   <li>Mixed spec and body variables in same package</li>
     * </ul>
     */
    @Test
    void packageBodyVariables_privateVariables() throws SQLException {
        // Oracle package with variables in BOTH spec (public) and body (private)
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.state_pkg AS
              g_public_counter INTEGER := 0;

              FUNCTION get_counters RETURN VARCHAR2;
              PROCEDURE increment_public;
              PROCEDURE increment_private;
            END state_pkg;
            """;

        // Package body with private variables
        // In real Oracle, body variables are only visible within the package
        String oraclePackageBody = """
            CREATE OR REPLACE PACKAGE BODY hr.state_pkg AS
              -- Private variables (declared in body, not spec)
              g_private_counter INTEGER := 0;
              g_internal_state VARCHAR2(20) := 'INIT';

              FUNCTION get_counters RETURN VARCHAR2 IS
              BEGIN
                RETURN 'Public: ' || g_public_counter || ', Private: ' || g_private_counter;
              END;

              PROCEDURE increment_public IS
              BEGIN
                g_public_counter := g_public_counter + 1;
              END;

              PROCEDURE increment_private IS
              BEGIN
                g_private_counter := g_private_counter + 1;
                g_internal_state := 'UPDATED';
              END;
            END state_pkg;
            """;

        // Extract package context from spec
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext("hr", "state_pkg", oraclePackageSpec);

        // Should have 1 variable from spec
        assertEquals(1, packageContext.getVariables().size(), "Should extract 1 variable from spec");
        assertTrue(packageContext.hasVariable("g_public_counter"), "Should have g_public_counter from spec");

        // Parse package body and extract body variables
        me.christianrobert.orapgsync.transformer.parser.AntlrParser parser = new me.christianrobert.orapgsync.transformer.parser.AntlrParser();
        me.christianrobert.orapgsync.transformer.parser.ParseResult bodyParseResult = parser.parsePackageBody(oraclePackageBody);
        me.christianrobert.orapgsync.antlr.PlSqlParser.Create_package_bodyContext bodyAst =
            (me.christianrobert.orapgsync.antlr.PlSqlParser.Create_package_bodyContext) bodyParseResult.getTree();

        // Extract body variables
        extractor.extractBodyVariables(bodyAst, packageContext);

        // Should now have 3 variables total (1 from spec + 2 from body)
        assertEquals(3, packageContext.getVariables().size(), "Should have 3 variables total (spec + body)");
        assertTrue(packageContext.hasVariable("g_public_counter"), "Should have g_public_counter from spec");
        assertTrue(packageContext.hasVariable("g_private_counter"), "Should have g_private_counter from body");
        assertTrue(packageContext.hasVariable("g_internal_state"), "Should have g_internal_state from body");

        // Generate and execute helpers for ALL variables
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        for (String helperSql : helperSqls) {
            executeUpdate(helperSql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.state_pkg", packageContext);

        // Transform function that uses BOTH public and private variables
        String getCountersOracle = """
            FUNCTION get_counters RETURN VARCHAR2 IS
            BEGIN
              RETURN 'Public: ' || g_public_counter || ', Private: ' || g_private_counter;
            END;
            """;

        TransformationResult result = transformationService.transformFunction(
            getCountersOracle,
            "hr",
            indices,
            packageContextCache,
            "get_counters",
            "state_pkg"
        );

        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());

        System.out.println("=== Transformed get_counters (uses body variable) ===");
        System.out.println(result.getPostgresSql());
        System.out.println("=====================================================");

        // Verify transformation uses getters for BOTH variables
        assertTrue(result.getPostgresSql().contains("state_pkg__get_g_public_counter()"),
            "Should use getter for g_public_counter (spec variable)");
        assertTrue(result.getPostgresSql().contains("state_pkg__get_g_private_counter()"),
            "Should use getter for g_private_counter (body variable)");

        // Execute function
        executeUpdate(result.getPostgresSql());

        // Verify it works
        List<Map<String, Object>> rows = executeQuery(
            "SELECT hr.state_pkg__get_counters() AS counters"
        );
        String counters = (String) rows.get(0).get("counters");
        assertTrue(counters.contains("Public: 0"), "Should include public counter");
        assertTrue(counters.contains("Private: 0"), "Should include private counter");

        System.out.println("✓ Counters string: " + counters);
        System.out.println("\n✅ PACKAGE BODY VARIABLES TEST PASSED!");
    }

    /**
     * Test 4: Package variable assignment with complex expression.
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

    // =========================================================================
    // Phase 6: Complex Type Integration Tests (RECORD, TABLE OF, INDEX BY)
    // =========================================================================

    /**
     * Test 5: RECORD type package variable - field access and assignment.
     *
     * <p>Validates:
     * <ul>
     *   <li>RECORD type extraction from package spec</li>
     *   <li>RECORD variable getter returns jsonb</li>
     *   <li>RECORD variable setter accepts jsonb</li>
     *   <li>Field access transformation (->> with type cast)</li>
     *   <li>Field assignment transformation (jsonb_set)</li>
     *   <li>End-to-end execution in PostgreSQL</li>
     * </ul>
     */
    @Test
    void recordTypeVariable_fieldAccessAndAssignment() throws SQLException {
        // Oracle package with RECORD type
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.salary_pkg AS
              TYPE salary_range_t IS RECORD (
                min_sal NUMBER,
                max_sal NUMBER,
                currency VARCHAR2(3)
              );

              g_range salary_range_t;

              FUNCTION get_min_salary RETURN NUMBER;
              FUNCTION get_max_salary RETURN NUMBER;
              PROCEDURE set_salary_range(p_min NUMBER, p_max NUMBER);
              FUNCTION get_range_info RETURN VARCHAR2;
            END salary_pkg;
            """;

        System.out.println("\n=== RECORD TYPE INTEGRATION TEST ===");

        // Extract package context (should include RECORD type)
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext("hr", "salary_pkg", oraclePackageSpec);

        // Verify type extraction
        assertNotNull(packageContext.getType("salary_range_t"), "Should extract RECORD type");
        assertTrue(packageContext.hasVariable("g_range"), "Should have g_range variable");

        System.out.println("✓ Extracted RECORD type: salary_range_t");
        System.out.println("✓ Extracted variable: g_range");

        // Generate and execute helpers
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        System.out.println("\n=== Generated Helper Functions ===");
        for (String sql : helperSqls) {
            System.out.println(sql);
            System.out.println("---");
            executeUpdate(sql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.salary_pkg", packageContext);

        // Transform get_min_salary (field access on RHS)
        String getMinSalaryOracle = """
            FUNCTION get_min_salary RETURN NUMBER IS
            BEGIN
              RETURN g_range.min_sal;
            END;
            """;

        TransformationResult getMinResult = transformationService.transformFunction(
            getMinSalaryOracle, "hr", indices, packageContextCache, "get_min_salary", "salary_pkg"
        );
        assertTrue(getMinResult.isSuccess(), "get_min_salary transformation should succeed: " + getMinResult.getErrorMessage());

        System.out.println("\n=== Transformed get_min_salary ===");
        System.out.println(getMinResult.getPostgresSql());

        // Verify field access transformation
        assertTrue(getMinResult.getPostgresSql().contains("salary_pkg__get_g_range()"),
            "Should use getter for g_range");
        assertTrue(getMinResult.getPostgresSql().contains("->>'min_sal'"),
            "Should access min_sal field with ->>");
        assertTrue(getMinResult.getPostgresSql().contains("::numeric"),
            "Should cast to numeric");

        executeUpdate(getMinResult.getPostgresSql());

        // Transform set_salary_range (field assignment on LHS)
        String setSalaryRangeOracle = """
            PROCEDURE set_salary_range(p_min NUMBER, p_max NUMBER) IS
            BEGIN
              g_range.min_sal := p_min;
              g_range.max_sal := p_max;
              g_range.currency := 'USD';
            END;
            """;

        TransformationResult setRangeResult = transformationService.transformProcedure(
            setSalaryRangeOracle, "hr", indices, packageContextCache, "set_salary_range", "salary_pkg"
        );
        assertTrue(setRangeResult.isSuccess(), "set_salary_range transformation should succeed: " + setRangeResult.getErrorMessage());

        System.out.println("\n=== Transformed set_salary_range ===");
        System.out.println(setRangeResult.getPostgresSql());

        // Verify field assignment transformation
        assertTrue(setRangeResult.getPostgresSql().contains("PERFORM"),
            "Should use PERFORM for setter");
        assertTrue(setRangeResult.getPostgresSql().contains("salary_pkg__set_g_range"),
            "Should use setter");
        assertTrue(setRangeResult.getPostgresSql().contains("jsonb_set"),
            "Should use jsonb_set for field assignment");

        executeUpdate(setRangeResult.getPostgresSql());

        // Execute and verify
        System.out.println("\n=== Executing and Verifying ===");

        connection.setAutoCommit(false);
        try {
            // Set salary range
            executeUpdate("SELECT hr.salary_pkg__set_salary_range(1000, 5000)");

            // Get min salary
            List<Map<String, Object>> minResult = executeQuery(
                "SELECT hr.salary_pkg__get_min_salary() AS min_sal"
            );
            assertEquals(1000, ((Number) minResult.get(0).get("min_sal")).intValue(),
                "Min salary should be 1000");
            System.out.println("✓ Min salary: 1000");

            // Verify the full RECORD via getter
            List<Map<String, Object>> rangeResult = executeQuery(
                "SELECT hr.salary_pkg__get_g_range() AS g_range"
            );
            // Note: PostgreSQL jsonb returns as PGobject, use toString()
            String rangeJson = rangeResult.get(0).get("g_range").toString();
            System.out.println("✓ Full range JSON: " + rangeJson);
            assertTrue(rangeJson.contains("\"min_sal\""), "Should have min_sal field");
            assertTrue(rangeJson.contains("\"max_sal\""), "Should have max_sal field");
            assertTrue(rangeJson.contains("\"currency\""), "Should have currency field");

            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }

        System.out.println("\n✅ RECORD TYPE INTEGRATION TEST PASSED!");
    }

    /**
     * Test 6: TABLE OF type package variable - element access and assignment.
     *
     * <p>Validates:
     * <ul>
     *   <li>TABLE OF type extraction from package spec</li>
     *   <li>Array variable getter returns jsonb</li>
     *   <li>Element access transformation (->index with 1-based to 0-based)</li>
     *   <li>Element assignment transformation (jsonb_set)</li>
     *   <li>End-to-end execution in PostgreSQL</li>
     * </ul>
     */
    @Test
    void tableOfVariable_elementAccessAndAssignment() throws SQLException {
        // Oracle package with TABLE OF type
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.scores_pkg AS
              TYPE num_list_t IS TABLE OF NUMBER INDEX BY PLS_INTEGER;

              g_scores num_list_t;

              FUNCTION get_score(p_index NUMBER) RETURN NUMBER;
              PROCEDURE set_score(p_index NUMBER, p_value NUMBER);
              FUNCTION get_total RETURN NUMBER;
            END scores_pkg;
            """;

        System.out.println("\n=== TABLE OF TYPE INTEGRATION TEST ===");

        // Extract package context
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext("hr", "scores_pkg", oraclePackageSpec);

        // Verify type extraction
        assertNotNull(packageContext.getType("num_list_t"), "Should extract TABLE OF type");
        assertTrue(packageContext.hasVariable("g_scores"), "Should have g_scores variable");

        System.out.println("✓ Extracted TABLE OF type: num_list_t");
        System.out.println("✓ Extracted variable: g_scores");

        // Generate and execute helpers
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        for (String sql : helperSqls) {
            executeUpdate(sql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.scores_pkg", packageContext);

        // Transform get_score (element access on RHS)
        String getScoreOracle = """
            FUNCTION get_score(p_index NUMBER) RETURN NUMBER IS
            BEGIN
              RETURN g_scores(p_index);
            END;
            """;

        TransformationResult getScoreResult = transformationService.transformFunction(
            getScoreOracle, "hr", indices, packageContextCache, "get_score", "scores_pkg"
        );
        assertTrue(getScoreResult.isSuccess(), "get_score transformation should succeed: " + getScoreResult.getErrorMessage());

        System.out.println("\n=== Transformed get_score ===");
        System.out.println(getScoreResult.getPostgresSql());

        // Verify element access transformation
        assertTrue(getScoreResult.getPostgresSql().contains("scores_pkg__get_g_scores()"),
            "Should use getter for g_scores");
        assertTrue(getScoreResult.getPostgresSql().contains("p_index - 1"),
            "Should convert 1-based to 0-based index");

        executeUpdate(getScoreResult.getPostgresSql());

        // Transform set_score (element assignment on LHS)
        String setScoreOracle = """
            PROCEDURE set_score(p_index NUMBER, p_value NUMBER) IS
            BEGIN
              g_scores(p_index) := p_value;
            END;
            """;

        TransformationResult setScoreResult = transformationService.transformProcedure(
            setScoreOracle, "hr", indices, packageContextCache, "set_score", "scores_pkg"
        );
        assertTrue(setScoreResult.isSuccess(), "set_score transformation should succeed: " + setScoreResult.getErrorMessage());

        System.out.println("\n=== Transformed set_score ===");
        System.out.println(setScoreResult.getPostgresSql());

        // Verify element assignment transformation
        assertTrue(setScoreResult.getPostgresSql().contains("PERFORM"),
            "Should use PERFORM for setter");
        assertTrue(setScoreResult.getPostgresSql().contains("scores_pkg__set_g_scores"),
            "Should use setter");
        assertTrue(setScoreResult.getPostgresSql().contains("jsonb_set"),
            "Should use jsonb_set for element assignment");
        assertTrue(setScoreResult.getPostgresSql().contains("p_index - 1"),
            "Should convert index in assignment");

        executeUpdate(setScoreResult.getPostgresSql());

        // Execute and verify
        System.out.println("\n=== Executing and Verifying ===");

        connection.setAutoCommit(false);
        try {
            // Set scores (1-based Oracle indices)
            executeUpdate("SELECT hr.scores_pkg__set_score(1, 85)");
            executeUpdate("SELECT hr.scores_pkg__set_score(2, 90)");
            executeUpdate("SELECT hr.scores_pkg__set_score(3, 95)");

            // Get score at index 2
            List<Map<String, Object>> score2Result = executeQuery(
                "SELECT hr.scores_pkg__get_score(2) AS score"
            );
            assertEquals(90, ((Number) score2Result.get(0).get("score")).intValue(),
                "Score at index 2 should be 90");
            System.out.println("✓ Score at index 2: 90");

            // Get score at index 1
            List<Map<String, Object>> score1Result = executeQuery(
                "SELECT hr.scores_pkg__get_score(1) AS score"
            );
            assertEquals(85, ((Number) score1Result.get(0).get("score")).intValue(),
                "Score at index 1 should be 85");
            System.out.println("✓ Score at index 1: 85");

            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }

        System.out.println("\n✅ TABLE OF TYPE INTEGRATION TEST PASSED!");
    }

    /**
     * Test 7: INDEX BY VARCHAR2 type package variable - map access and assignment.
     *
     * <p>Validates:
     * <ul>
     *   <li>INDEX BY VARCHAR2 type extraction (associative array with string keys)</li>
     *   <li>Map variable getter returns jsonb</li>
     *   <li>Map element access transformation (->> with string key)</li>
     *   <li>Map element assignment transformation (jsonb_set)</li>
     *   <li>End-to-end execution in PostgreSQL</li>
     * </ul>
     */
    @Test
    void indexByVarchar2Variable_mapAccessAndAssignment() throws SQLException {
        // Oracle package with INDEX BY VARCHAR2 type (associative array / map)
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.dept_pkg AS
              TYPE dept_map_t IS TABLE OF VARCHAR2(100) INDEX BY VARCHAR2(20);

              g_dept_names dept_map_t;

              FUNCTION get_dept_name(p_code VARCHAR2) RETURN VARCHAR2;
              PROCEDURE set_dept_name(p_code VARCHAR2, p_name VARCHAR2);
            END dept_pkg;
            """;

        System.out.println("\n=== INDEX BY VARCHAR2 (MAP) TYPE INTEGRATION TEST ===");

        // Extract package context
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext("hr", "dept_pkg", oraclePackageSpec);

        // Verify type extraction
        assertNotNull(packageContext.getType("dept_map_t"), "Should extract INDEX BY type");
        assertTrue(packageContext.hasVariable("g_dept_names"), "Should have g_dept_names variable");

        System.out.println("✓ Extracted INDEX BY VARCHAR2 type: dept_map_t");
        System.out.println("✓ Extracted variable: g_dept_names");

        // Generate and execute helpers
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        for (String sql : helperSqls) {
            executeUpdate(sql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.dept_pkg", packageContext);

        // Transform get_dept_name (map access on RHS)
        String getDeptNameOracle = """
            FUNCTION get_dept_name(p_code VARCHAR2) RETURN VARCHAR2 IS
            BEGIN
              RETURN g_dept_names(p_code);
            END;
            """;

        TransformationResult getDeptResult = transformationService.transformFunction(
            getDeptNameOracle, "hr", indices, packageContextCache, "get_dept_name", "dept_pkg"
        );
        assertTrue(getDeptResult.isSuccess(), "get_dept_name transformation should succeed: " + getDeptResult.getErrorMessage());

        System.out.println("\n=== Transformed get_dept_name ===");
        System.out.println(getDeptResult.getPostgresSql());

        // Verify map access transformation (string key, no index conversion)
        assertTrue(getDeptResult.getPostgresSql().contains("dept_pkg__get_g_dept_names()"),
            "Should use getter for g_dept_names");
        assertTrue(getDeptResult.getPostgresSql().contains("->>") && getDeptResult.getPostgresSql().contains("p_code"),
            "Should access with ->> and string key");

        executeUpdate(getDeptResult.getPostgresSql());

        // Transform set_dept_name (map assignment on LHS)
        String setDeptNameOracle = """
            PROCEDURE set_dept_name(p_code VARCHAR2, p_name VARCHAR2) IS
            BEGIN
              g_dept_names(p_code) := p_name;
            END;
            """;

        TransformationResult setDeptResult = transformationService.transformProcedure(
            setDeptNameOracle, "hr", indices, packageContextCache, "set_dept_name", "dept_pkg"
        );
        assertTrue(setDeptResult.isSuccess(), "set_dept_name transformation should succeed: " + setDeptResult.getErrorMessage());

        System.out.println("\n=== Transformed set_dept_name ===");
        System.out.println(setDeptResult.getPostgresSql());

        // Verify map assignment transformation
        assertTrue(setDeptResult.getPostgresSql().contains("PERFORM"),
            "Should use PERFORM for setter");
        assertTrue(setDeptResult.getPostgresSql().contains("dept_pkg__set_g_dept_names"),
            "Should use setter");
        assertTrue(setDeptResult.getPostgresSql().contains("jsonb_set"),
            "Should use jsonb_set for map assignment");

        executeUpdate(setDeptResult.getPostgresSql());

        // Execute and verify
        System.out.println("\n=== Executing and Verifying ===");

        connection.setAutoCommit(false);
        try {
            // Set department names
            executeUpdate("SELECT hr.dept_pkg__set_dept_name('HR', 'Human Resources')");
            executeUpdate("SELECT hr.dept_pkg__set_dept_name('IT', 'Information Technology')");
            executeUpdate("SELECT hr.dept_pkg__set_dept_name('FIN', 'Finance')");

            // Get department name
            List<Map<String, Object>> hrResult = executeQuery(
                "SELECT hr.dept_pkg__get_dept_name('HR') AS name"
            );
            assertEquals("Human Resources", hrResult.get(0).get("name"),
                "HR department should be Human Resources");
            System.out.println("✓ HR = Human Resources");

            List<Map<String, Object>> itResult = executeQuery(
                "SELECT hr.dept_pkg__get_dept_name('IT') AS name"
            );
            assertEquals("Information Technology", itResult.get(0).get("name"),
                "IT department should be Information Technology");
            System.out.println("✓ IT = Information Technology");

            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }

        System.out.println("\n✅ INDEX BY VARCHAR2 (MAP) TYPE INTEGRATION TEST PASSED!");
    }

    /**
     * Test 8: Mixed complex types - RECORD with collection fields.
     *
     * <p>Validates:
     * <ul>
     *   <li>Package with multiple complex types working together</li>
     *   <li>Nested field access (RECORD field that is itself a collection)</li>
     *   <li>Mixed scalar and complex type operations</li>
     *   <li>End-to-end execution in PostgreSQL</li>
     * </ul>
     */
    @Test
    void mixedComplexTypes_recordFieldAccess() throws SQLException {
        // Oracle package with mixed complex types
        String oraclePackageSpec = """
            CREATE OR REPLACE PACKAGE hr.employee_pkg AS
              TYPE address_t IS RECORD (
                street VARCHAR2(100),
                city VARCHAR2(50),
                zip_code VARCHAR2(10)
              );

              TYPE employee_t IS RECORD (
                name VARCHAR2(100),
                salary NUMBER,
                address address_t
              );

              g_current_employee employee_t;
              g_employee_count INTEGER := 0;

              FUNCTION get_employee_name RETURN VARCHAR2;
              FUNCTION get_employee_salary RETURN NUMBER;
              PROCEDURE set_employee_info(p_name VARCHAR2, p_salary NUMBER);
              PROCEDURE increment_count;
            END employee_pkg;
            """;

        System.out.println("\n=== MIXED COMPLEX TYPES INTEGRATION TEST ===");

        // Extract package context
        PackageContextExtractor extractor = new PackageContextExtractor(new me.christianrobert.orapgsync.transformer.parser.AntlrParser());
        PackageContext packageContext = extractor.extractContext("hr", "employee_pkg", oraclePackageSpec);

        // Verify extraction
        assertNotNull(packageContext.getType("address_t"), "Should extract address_t type");
        assertNotNull(packageContext.getType("employee_t"), "Should extract employee_t type");
        assertTrue(packageContext.hasVariable("g_current_employee"), "Should have g_current_employee");
        assertTrue(packageContext.hasVariable("g_employee_count"), "Should have g_employee_count");

        System.out.println("✓ Extracted types: address_t, employee_t");
        System.out.println("✓ Extracted variables: g_current_employee (RECORD), g_employee_count (INTEGER)");

        // Generate and execute helpers
        PackageHelperGenerator generator = new PackageHelperGenerator();
        List<String> helperSqls = generator.generateHelperSql(packageContext);
        for (String sql : helperSqls) {
            executeUpdate(sql);
        }

        // Build package context cache
        Map<String, PackageContext> packageContextCache = new HashMap<>();
        packageContextCache.put("hr.employee_pkg", packageContext);

        // Transform get_employee_name (RECORD field access)
        String getNameOracle = """
            FUNCTION get_employee_name RETURN VARCHAR2 IS
            BEGIN
              RETURN g_current_employee.name;
            END;
            """;

        TransformationResult getNameResult = transformationService.transformFunction(
            getNameOracle, "hr", indices, packageContextCache, "get_employee_name", "employee_pkg"
        );
        assertTrue(getNameResult.isSuccess(), "get_employee_name transformation should succeed");

        System.out.println("\n=== Transformed get_employee_name ===");
        System.out.println(getNameResult.getPostgresSql());
        executeUpdate(getNameResult.getPostgresSql());

        // Transform set_employee_info (RECORD field assignment)
        String setInfoOracle = """
            PROCEDURE set_employee_info(p_name VARCHAR2, p_salary NUMBER) IS
            BEGIN
              g_current_employee.name := p_name;
              g_current_employee.salary := p_salary;
            END;
            """;

        TransformationResult setInfoResult = transformationService.transformProcedure(
            setInfoOracle, "hr", indices, packageContextCache, "set_employee_info", "employee_pkg"
        );
        assertTrue(setInfoResult.isSuccess(), "set_employee_info transformation should succeed");

        System.out.println("\n=== Transformed set_employee_info ===");
        System.out.println(setInfoResult.getPostgresSql());
        executeUpdate(setInfoResult.getPostgresSql());

        // Transform increment_count (scalar variable)
        String incrementOracle = """
            PROCEDURE increment_count IS
            BEGIN
              g_employee_count := g_employee_count + 1;
            END;
            """;

        TransformationResult incrementResult = transformationService.transformProcedure(
            incrementOracle, "hr", indices, packageContextCache, "increment_count", "employee_pkg"
        );
        assertTrue(incrementResult.isSuccess(), "increment_count transformation should succeed");
        executeUpdate(incrementResult.getPostgresSql());

        // Execute and verify
        System.out.println("\n=== Executing and Verifying ===");

        connection.setAutoCommit(false);
        try {
            // Set employee info
            executeUpdate("SELECT hr.employee_pkg__set_employee_info('John Doe', 75000)");
            executeUpdate("SELECT hr.employee_pkg__increment_count()");

            // Get employee name
            List<Map<String, Object>> nameResult = executeQuery(
                "SELECT hr.employee_pkg__get_employee_name() AS name"
            );
            assertEquals("John Doe", nameResult.get(0).get("name"),
                "Employee name should be John Doe");
            System.out.println("✓ Employee name: John Doe");

            // Get employee count
            List<Map<String, Object>> countResult = executeQuery(
                "SELECT hr.employee_pkg__get_g_employee_count() AS count"
            );
            assertEquals(1, ((Number) countResult.get(0).get("count")).intValue(),
                "Employee count should be 1");
            System.out.println("✓ Employee count: 1");

            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }

        System.out.println("\n✅ MIXED COMPLEX TYPES INTEGRATION TEST PASSED!");
    }
}
