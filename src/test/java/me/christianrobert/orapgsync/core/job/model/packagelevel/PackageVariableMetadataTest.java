package me.christianrobert.orapgsync.core.job.model.packagelevel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackageVariableMetadataTest {

    @Test
    void testConstructorAndBasicGetters() {
        // Given
        String schema = "HR";
        String packageName = "EMP_PKG";
        String variableName = "g_counter";
        String dataType = "INTEGER";

        // When
        PackageVariableMetadata metadata = new PackageVariableMetadata(schema, packageName, variableName, dataType);

        // Then
        assertEquals(schema, metadata.getSchema());
        assertEquals(packageName, metadata.getPackageName());
        assertEquals(variableName, metadata.getVariableName());
        assertEquals(dataType, metadata.getDataType());
        assertNull(metadata.getDefaultValue()); // null by default
        assertFalse(metadata.isConstant()); // false by default
    }

    @Test
    void testSettersForOptionalFields() {
        // Given
        PackageVariableMetadata metadata = new PackageVariableMetadata("HR", "EMP_PKG", "g_counter", "INTEGER");

        // When
        metadata.setDefaultValue("0");
        metadata.setConstant(true);

        // Then
        assertEquals("0", metadata.getDefaultValue());
        assertTrue(metadata.isConstant());
    }

    @Test
    void testQualifiedKey() {
        // Given
        PackageVariableMetadata metadata = new PackageVariableMetadata("HR", "EMP_PKG", "g_counter", "INTEGER");

        // When
        String qualifiedKey = metadata.getQualifiedKey();

        // Then
        assertEquals("hr.emp_pkg.g_counter", qualifiedKey); // lowercase
    }

    @Test
    void testQualifiedKeyLowercase() {
        // Given - mixed case input
        PackageVariableMetadata metadata = new PackageVariableMetadata("Hr", "Emp_Pkg", "G_Counter", "INTEGER");

        // When
        String qualifiedKey = metadata.getQualifiedKey();

        // Then
        assertEquals("hr.emp_pkg.g_counter", qualifiedKey); // normalized to lowercase
    }

    @Test
    void testToString() {
        // Given
        PackageVariableMetadata metadata = new PackageVariableMetadata("HR", "EMP_PKG", "g_counter", "INTEGER");
        metadata.setDefaultValue("0");
        metadata.setConstant(false);

        // When
        String result = metadata.toString();

        // Then
        assertNotNull(result);
        assertTrue(result.contains("HR"));
        assertTrue(result.contains("EMP_PKG"));
        assertTrue(result.contains("g_counter"));
        assertTrue(result.contains("INTEGER"));
        assertTrue(result.contains("0"));
    }

    @Test
    void testConstantVariable() {
        // Given
        PackageVariableMetadata metadata = new PackageVariableMetadata("HR", "EMP_PKG", "c_max_retries", "INTEGER");
        metadata.setDefaultValue("5");
        metadata.setConstant(true);

        // Then
        assertTrue(metadata.isConstant());
        assertEquals("5", metadata.getDefaultValue());
    }

    @Test
    void testVarchar2Type() {
        // Given
        PackageVariableMetadata metadata = new PackageVariableMetadata("HR", "EMP_PKG", "g_status", "VARCHAR2(20)");
        metadata.setDefaultValue("'ACTIVE'");

        // Then
        assertEquals("VARCHAR2(20)", metadata.getDataType());
        assertEquals("'ACTIVE'", metadata.getDefaultValue());
    }
}
