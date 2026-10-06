package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.packagevariable.PackageContext;
import me.christianrobert.orapgsync.transformer.packagevariable.PackageContextExtractor;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PackageContextExtractor.
 * Verifies package spec parsing and variable extraction.
 */
class PackageContextExtractorTest {

    private AntlrParser antlrParser;
    private PackageContextExtractor extractor;

    @BeforeEach
    void setUp() {
        antlrParser = new AntlrParser();
        extractor = new PackageContextExtractor(antlrParser);
    }

    @Test
    void extractSimpleVariable() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              g_counter INTEGER := 0;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        assertNotNull(context);
        assertEquals("hr", context.getSchema());
        assertEquals("emp_pkg", context.getPackageName());
        assertEquals(1, context.getVariables().size());
        assertTrue(context.hasVariable("g_counter"));

        PackageContext.PackageVariable var = context.getVariable("g_counter");
        assertEquals("g_counter", var.getVariableName());
        assertEquals("INTEGER", var.getDataType());
        assertEquals("0", var.getDefaultValue());
        assertFalse(var.isConstant());
    }

    @Test
    void extractConstantVariable() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              c_max_salary CONSTANT NUMBER := 10000;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        assertTrue(context.hasVariable("c_max_salary"));
        PackageContext.PackageVariable var = context.getVariable("c_max_salary");
        assertTrue(var.isConstant());
        assertEquals("NUMBER", var.getDataType());
        assertEquals("10000", var.getDefaultValue());
    }

    @Test
    void extractMultipleVariables() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              g_counter INTEGER := 0;
              g_status VARCHAR2(20) := 'ACTIVE';
              c_max_salary CONSTANT NUMBER := 10000;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        assertEquals(3, context.getVariables().size());
        assertTrue(context.hasVariable("g_counter"));
        assertTrue(context.hasVariable("g_status"));
        assertTrue(context.hasVariable("c_max_salary"));

        // Check g_status details
        PackageContext.PackageVariable status = context.getVariable("g_status");
        assertEquals("VARCHAR2(20)", status.getDataType());
        assertEquals("'ACTIVE'", status.getDefaultValue());
        assertFalse(status.isConstant());
    }

    @Test
    void extractVariableCaseInsensitive() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              G_COUNTER INTEGER := 0;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        // Should work with lowercase lookup (Oracle is case-insensitive)
        assertTrue(context.hasVariable("g_counter"));
        assertTrue(context.hasVariable("G_COUNTER"));
        assertTrue(context.hasVariable("G_Counter"));
    }

    @Test
    void extractPackageWithNoVariables() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              FUNCTION get_salary(p_empno NUMBER) RETURN NUMBER;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        assertNotNull(context);
        assertEquals(0, context.getVariables().size());
    }

    @Test
    void cacheKeyIsLowercase() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              g_counter INTEGER := 0;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("HR", "EMP_PKG", packageSpec);

        assertEquals("hr.emp_pkg", context.getCacheKey());
    }

    @Test
    void extractVariableWithNullDefault() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              g_counter INTEGER;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        PackageContext.PackageVariable var = context.getVariable("g_counter");
        assertNull(var.getDefaultValue());
    }

    @Test
    void extractVariableWithDateType() {
        String packageSpec = """
            CREATE OR REPLACE PACKAGE emp_pkg AS
              g_hire_date DATE := SYSDATE;
            END emp_pkg;
            """;

        PackageContext context = extractor.extractContext("hr", "emp_pkg", packageSpec);

        PackageContext.PackageVariable var = context.getVariable("g_hire_date");
        assertEquals("DATE", var.getDataType());
        assertEquals("SYSDATE", var.getDefaultValue());
    }
}
