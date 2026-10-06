package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.FieldDefinition;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;
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

        // Should use set_config for transaction-local variables
        assertTrue(initFunction.contains("set_config"));
        assertTrue(initFunction.contains("hr.emp_pkg.g_counter"));
        assertTrue(initFunction.contains("true")); // is_local = true (transaction-local, auto-reset on commit)
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

    // ========================================================================
    // COMPLEX TYPE TESTS (Phase 1: Package Variable Complex Types)
    // ========================================================================

    @Test
    void recordVariableGetterReturnsJsonb() {
        PackageContext context = new PackageContext("hr", "emp_pkg");

        // Add RECORD type definition
        InlineTypeDefinition recordType = new InlineTypeDefinition(
            "salary_range_t",
            TypeCategory.RECORD,
            null,  // No element type for RECORD
            List.of(
                new FieldDefinition("min_sal", "NUMBER", "numeric"),
                new FieldDefinition("max_sal", "NUMBER", "numeric")
            ),
            ConversionStrategy.JSONB,
            null
        );
        context.addType(recordType);

        // Add variable of RECORD type
        context.addVariable(new PackageContext.PackageVariable(
            "g_range", "salary_range_t", null, false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find getter function
        String getterFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_range"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Getter not found"));

        // Should return jsonb, not text or some other type
        assertTrue(getterFunction.contains("RETURNS jsonb"),
            "RECORD variable getter should return jsonb. Actual: " + getterFunction);

        // Should use jsonb cast
        assertTrue(getterFunction.contains("::jsonb"),
            "Getter should cast to jsonb");

        // Should use empty object '{}' as default
        assertTrue(getterFunction.contains("'{}'"),
            "RECORD should default to empty object '{}'");

        // Should use NULLIF to handle empty string from current_setting
        assertTrue(getterFunction.contains("NULLIF"),
            "Getter should use NULLIF for empty string handling. Actual: " + getterFunction);
    }

    @Test
    void recordVariableSetterAcceptsJsonb() {
        PackageContext context = new PackageContext("hr", "emp_pkg");

        // Add RECORD type definition
        InlineTypeDefinition recordType = new InlineTypeDefinition(
            "salary_range_t",
            TypeCategory.RECORD,
            null,
            List.of(
                new FieldDefinition("min_sal", "NUMBER", "numeric"),
                new FieldDefinition("max_sal", "NUMBER", "numeric")
            ),
            ConversionStrategy.JSONB,
            null
        );
        context.addType(recordType);

        // Add variable of RECORD type
        context.addVariable(new PackageContext.PackageVariable(
            "g_range", "salary_range_t", null, false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find setter function
        String setterFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__set_g_range"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Setter not found"));

        // Should accept jsonb parameter
        assertTrue(setterFunction.contains("p_value jsonb"),
            "RECORD variable setter should accept jsonb. Actual: " + setterFunction);
    }

    @Test
    void tableOfVariableUsesJsonbArray() {
        PackageContext context = new PackageContext("hr", "emp_pkg");

        // Add TABLE OF type definition
        InlineTypeDefinition tableOfType = new InlineTypeDefinition(
            "num_list_t",
            TypeCategory.TABLE_OF,
            "NUMBER",  // Element type
            null,
            ConversionStrategy.JSONB,
            null
        );
        context.addType(tableOfType);

        // Add variable of TABLE OF type
        context.addVariable(new PackageContext.PackageVariable(
            "g_numbers", "num_list_t", null, false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find getter function
        String getterFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_numbers"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Getter not found"));

        // Should return jsonb
        assertTrue(getterFunction.contains("RETURNS jsonb"),
            "TABLE OF variable getter should return jsonb");

        // Should use empty array '[]' as default
        assertTrue(getterFunction.contains("'[]'"),
            "TABLE OF should default to empty array '[]'");
    }

    @Test
    void indexByVariableUsesJsonbObject() {
        PackageContext context = new PackageContext("hr", "emp_pkg");

        // Add INDEX BY type definition
        InlineTypeDefinition indexByType = new InlineTypeDefinition(
            "dept_map_t",
            TypeCategory.INDEX_BY,
            "VARCHAR2(100)",  // Value type
            null,
            ConversionStrategy.JSONB,
            null,
            "VARCHAR2(50)"  // Index key type
        );
        context.addType(indexByType);

        // Add variable of INDEX BY type
        context.addVariable(new PackageContext.PackageVariable(
            "g_dept_names", "dept_map_t", null, false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find getter function
        String getterFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_dept_names"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Getter not found"));

        // Should return jsonb
        assertTrue(getterFunction.contains("RETURNS jsonb"),
            "INDEX BY variable getter should return jsonb");

        // Should use empty object '{}' as default (not array)
        assertTrue(getterFunction.contains("'{}'"),
            "INDEX BY should default to empty object '{}'");
    }

    @Test
    void initializeFunctionSetsJsonbDefaults() {
        PackageContext context = new PackageContext("hr", "emp_pkg");

        // Add RECORD type and variable
        InlineTypeDefinition recordType = new InlineTypeDefinition(
            "salary_range_t",
            TypeCategory.RECORD,
            null,
            List.of(new FieldDefinition("min_sal", "NUMBER", "numeric")),
            ConversionStrategy.JSONB,
            null
        );
        context.addType(recordType);
        context.addVariable(new PackageContext.PackageVariable(
            "g_range", "salary_range_t", null, false
        ));

        // Add TABLE OF type and variable
        InlineTypeDefinition tableOfType = new InlineTypeDefinition(
            "num_list_t",
            TypeCategory.TABLE_OF,
            "NUMBER",
            null,
            ConversionStrategy.JSONB,
            null
        );
        context.addType(tableOfType);
        context.addVariable(new PackageContext.PackageVariable(
            "g_numbers", "num_list_t", null, false
        ));

        // Add scalar variable for comparison
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // Find initialize function
        String initFunction = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__initialize"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Initialize function not found"));

        // RECORD variable should be initialized with '{}'
        assertTrue(initFunction.contains("g_range") && initFunction.contains("'{}'"),
            "RECORD should be initialized with '{}'");

        // TABLE OF variable should be initialized with '[]'
        assertTrue(initFunction.contains("g_numbers") && initFunction.contains("'[]'"),
            "TABLE OF should be initialized with '[]'");

        // Scalar variable should still work (initialized with '0')
        assertTrue(initFunction.contains("g_counter") && initFunction.contains("'0'"),
            "Scalar should be initialized with '0'");
    }

    @Test
    void mixedScalarAndComplexTypes() {
        PackageContext context = new PackageContext("hr", "emp_pkg");

        // Add RECORD type and variable
        InlineTypeDefinition recordType = new InlineTypeDefinition(
            "emp_rec_t",
            TypeCategory.RECORD,
            null,
            List.of(new FieldDefinition("name", "VARCHAR2(100)", "text")),
            ConversionStrategy.JSONB,
            null
        );
        context.addType(recordType);
        context.addVariable(new PackageContext.PackageVariable(
            "g_employee", "emp_rec_t", null, false
        ));

        // Add scalar variables
        context.addVariable(new PackageContext.PackageVariable(
            "g_counter", "INTEGER", "0", false
        ));
        context.addVariable(new PackageContext.PackageVariable(
            "g_status", "VARCHAR2(20)", "'ACTIVE'", false
        ));

        List<String> sqlStatements = generator.generateHelperSql(context);

        // 1 initialize + 3 getters + 3 setters = 7 statements
        assertEquals(7, sqlStatements.size());

        // RECORD getter returns jsonb
        String recordGetter = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_employee"))
            .findFirst()
            .orElseThrow();
        assertTrue(recordGetter.contains("RETURNS jsonb"));

        // Scalar getter returns integer
        String counterGetter = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_counter"))
            .findFirst()
            .orElseThrow();
        assertTrue(counterGetter.contains("RETURNS integer"));

        // Scalar getter returns text
        String statusGetter = sqlStatements.stream()
            .filter(sql -> sql.contains("emp_pkg__get_g_status"))
            .findFirst()
            .orElseThrow();
        assertTrue(statusGetter.contains("RETURNS text"));
    }
}
