package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageHelperGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PackageHelperGenerator.
 * Verifies helper SQL generation (initialize, getters, setters).
 */
class PackageHelperGeneratorTest {

    private PackageHelperGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new PackageHelperGenerator();
    }

    @Test
    void generateHelpersForSingleVariable() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Should have: 1 initialize + 1 getter + 1 setter = 3 statements
        assertEquals(3, sqlStatements.size());

        // Check initialize function exists
        assertTrue(sqlStatements.stream().anyMatch(sql -> sql.contains("emp_pkg__initialize")));

        // Check getter exists
        assertTrue(sqlStatements.stream().anyMatch(sql ->
            sql.contains("emp_pkg__get_g_counter") && sql.contains("RETURNS")));

        // Check setter exists
        assertTrue(sqlStatements.stream().anyMatch(sql ->
            sql.contains("emp_pkg__set_g_counter") && sql.contains("p_value")));
    }

    @Test
    void generateHelpersForConstant() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        context.addVariable(new PackageContext.PackageVariable(
            "c_max_salary", "NUMBER", "10000", true
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Should have: 1 initialize + 1 getter + NO setter (constant) = 2 statements
        assertEquals(2, sqlStatements.size());

        // Check getter exists
        assertTrue(sqlStatements.stream().anyMatch(sql ->
            sql.contains("emp_pkg__get_c_max_salary")));

        // Check NO setter exists (constant)
        assertFalse(sqlStatements.stream().anyMatch(sql ->
            sql.contains("emp_pkg__set_c_max_salary")));
    }

    @Test
    void generateHelpersForMultipleVariables() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));
        context.addVariable(new PackageContext.PackageVariable(
            "g_status", "VARCHAR2(20)", "'ACTIVE'", false
        ));
        context.addVariable(new PackageContext.PackageVariable(
            "c_max_salary", "NUMBER", "10000", true
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // 1 initialize + 3 getters + 2 setters (no setter for constant) = 6 statements
        assertEquals(6, sqlStatements.size());

        // Check all getters exist
        assertTrue(sqlStatements.stream().anyMatch(sql -> sql.contains("emp_pkg__get_g_counter")));
        assertTrue(sqlStatements.stream().anyMatch(sql -> sql.contains("emp_pkg__get_g_status")));
        assertTrue(sqlStatements.stream().anyMatch(sql -> sql.contains("emp_pkg__get_c_max_salary")));

        // Check only 2 setters exist (not for constant)
        long setterCount = sqlStatements.stream()
            .filter(sql -> sql.contains("__set_"))
            .count();
        assertEquals(2, setterCount);
    }

    @Test
    void initializeFunctionUsesSetConfig() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find initialize function
        String initFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__initialize"))
            .findFirst()
            .orElseThrow();

        // Should use set_config for session-level variables
        assertTrue(initFunction.contains("set_config"));
        assertTrue(initFunction.contains("hr.emp_pkg.g_counter"));
        assertTrue(initFunction.contains("false")); // is_local = false (session-level)
    }

    @Test
    void getterUsesCurrentSetting() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find getter function
        String getterFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_counter"))
            .findFirst()
            .orElseThrow();

        // Should use current_setting to retrieve value
        assertTrue(getterFunction.contains("current_setting"));
        assertTrue(getterFunction.contains("hr.emp_pkg.g_counter"));
    }

    @Test
    void setterUsesSetConfig() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find setter function
        String setterFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__set_g_counter"))
            .findFirst()
            .orElseThrow();

        // Should use set_config to store value
        assertTrue(setterFunction.contains("set_config"));
        assertTrue(setterFunction.contains("p_value"));
        assertTrue(setterFunction.contains("false")); // is_local = false
    }

    @Test
    void schemaQualificationIsLowercase() {
        PackageContext context = new PackageContext("HR", "EMP_PKG");
        context.addVariable(new PackageContext.PackageVariable(
            "G_COUNTER", "INTEGER", "0", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // All function names should be lowercase
        for (String sql : sqlStatements) {
            if (sql.contains("CREATE OR REPLACE FUNCTION")) {
                assertTrue(sql.contains("hr.emp_pkg__"), "Schema and package should be lowercase");
                assertFalse(sql.contains("HR.EMP_PKG"), "Should not contain uppercase schema/package");
            }
        }
    }

    @Test
    void noVariablesGeneratesOnlyInitialize() {
        PackageContext context = new PackageContext("hr", "emp_pkg");
        // No variables added

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Should only have initialize function (empty body)
        assertEquals(1, sqlStatements.size());
        assertTrue(sqlStatements.get(0).contains("emp_pkg__initialize"));
    }
}
