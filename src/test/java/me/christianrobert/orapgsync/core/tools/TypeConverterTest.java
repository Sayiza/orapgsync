package me.christianrobert.orapgsync.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for TypeConverter.toPostgre() method.
 *
 * Purpose: Verify type mapping from Oracle to PostgreSQL data types.
 * Critical for LOB→oid migration (BLOB/CLOB/NCLOB must map to oid for Java @Lob compatibility).
 */
class TypeConverterTest {

    // ========== LOB Type Mappings (Critical for @Lob compatibility) ==========

    @Test
    void toPostgre_blobMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("blob"),
                "BLOB should map to oid for Java @Lob compatibility");
    }

    @Test
    void toPostgre_blobUpperCaseMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("BLOB"),
                "BLOB (uppercase) should map to oid");
    }

    @Test
    void toPostgre_clobMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("clob"),
                "CLOB should map to oid for Java @Lob compatibility");
    }

    @Test
    void toPostgre_clobUpperCaseMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("CLOB"),
                "CLOB (uppercase) should map to oid");
    }

    @Test
    void toPostgre_nclobMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("nclob"),
                "NCLOB should map to oid for Java @Lob compatibility");
    }

    @Test
    void toPostgre_nclobUpperCaseMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("NCLOB"),
                "NCLOB (uppercase) should map to oid");
    }

    // ========== Obsolete LOB Types (Keep existing mapping) ==========

    @Test
    void toPostgre_longRemainsVarchar() {
        assertEquals("varchar", TypeConverter.toPostgre("long"),
                "LONG (obsolete) should remain varchar (no @Lob expected)");
    }

    @Test
    void toPostgre_longRawRemainsBytea() {
        assertEquals("bytea", TypeConverter.toPostgre("long raw"),
                "LONG RAW (obsolete) should remain bytea (no @Lob expected)");
    }

    // ========== Other LOB Types ==========

    @Test
    void toPostgre_bfileRemainsText() {
        assertEquals("text", TypeConverter.toPostgre("bfile"),
                "BFILE (external file reference) should remain text");
    }

    // ========== Numeric Types ==========

    @Test
    void toPostgre_numberMapsToNumeric() {
        assertEquals("numeric", TypeConverter.toPostgre("number"),
                "NUMBER should map to numeric");
    }

    @Test
    void toPostgre_numberWithPrecisionMapsToNumeric() {
        assertEquals("numeric", TypeConverter.toPostgre("number(10,2)"),
                "NUMBER(10,2) should map to numeric");
    }

    @Test
    void toPostgre_integerMapsToInteger() {
        assertEquals("integer", TypeConverter.toPostgre("integer"),
                "INTEGER should map to integer");
    }

    // ========== Character Types ==========

    @Test
    void toPostgre_varchar2MapsToText() {
        assertEquals("text", TypeConverter.toPostgre("varchar2"),
                "VARCHAR2 should map to text");
    }

    @Test
    void toPostgre_varchar2WithSizeMapsToText() {
        assertEquals("text", TypeConverter.toPostgre("varchar2(100)"),
                "VARCHAR2(100) should map to text");
    }

    @Test
    void toPostgre_charMapsToText() {
        assertEquals("text", TypeConverter.toPostgre("char"),
                "CHAR should map to text");
    }

    // ========== Date/Time Types ==========

    @Test
    void toPostgre_dateMapsToTimestamp() {
        assertEquals("timestamp", TypeConverter.toPostgre("date"),
                "DATE should map to timestamp");
    }

    @Test
    void toPostgre_timestampMapsToTimestamp() {
        assertEquals("timestamp", TypeConverter.toPostgre("timestamp"),
                "TIMESTAMP should map to timestamp");
    }

    @Test
    void toPostgre_timestampWithSizeMapsToTimestamp() {
        assertEquals("timestamp", TypeConverter.toPostgre("timestamp(6)"),
                "TIMESTAMP(6) should map to timestamp");
    }

    // ========== NULL Input ==========

    @Test
    void toPostgre_nullReturnsNull() {
        assertEquals(null, TypeConverter.toPostgre(null),
                "NULL input should return null");
    }

    // ========== Case Insensitivity ==========

    @Test
    void toPostgre_mixedCaseBlobMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("BlOb"),
                "Mixed case BLOB should map to oid (case insensitive)");
    }

    @Test
    void toPostgre_mixedCaseClobMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("ClOb"),
                "Mixed case CLOB should map to oid (case insensitive)");
    }

    // ========== Whitespace Handling ==========

    @Test
    void toPostgre_blobWithWhitespaceMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("  blob  "),
                "BLOB with surrounding whitespace should map to oid (trimmed)");
    }

    @Test
    void toPostgre_clobWithWhitespaceMapsToOid() {
        assertEquals("oid", TypeConverter.toPostgre("  clob  "),
                "CLOB with surrounding whitespace should map to oid (trimmed)");
    }

    // ========== Custom Types (Pass-through) ==========

    @Test
    void toPostgre_customTypePassesThrough() {
        assertEquals("hr.address_type", TypeConverter.toPostgre("hr.address_type"),
                "Custom user-defined type should pass through unchanged");
    }

    @Test
    void toPostgre_unknownTypePassesThrough() {
        assertEquals("unknown_type", TypeConverter.toPostgre("unknown_type"),
                "Unknown type should pass through unchanged (lowercase)");
    }
}
