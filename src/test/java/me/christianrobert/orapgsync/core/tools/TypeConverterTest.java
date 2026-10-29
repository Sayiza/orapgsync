package me.christianrobert.orapgsync.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    // ========== Data-dictionary spellings ==========
    //
    // ALL_ARGUMENTS does not spell every type the way PL/SQL source does. Both spellings below
    // name a type PostgreSQL has an exact equivalent for, so both must map; passing either
    // through produces SQL PostgreSQL rejects at the slash or the space, which is how these
    // were found.

    @Test
    void toPostgre_plsqlBooleanMapsToBoolean() {
        assertEquals("boolean", TypeConverter.toPostgre("PL/SQL BOOLEAN"),
                "ALL_ARGUMENTS spells a package member's BOOLEAN as 'PL/SQL BOOLEAN'");
    }

    @Test
    void toPostgre_plsqlBooleanLowerCaseMapsToBoolean() {
        assertEquals("boolean", TypeConverter.toPostgre("pl/sql boolean"),
                "'PL/SQL BOOLEAN' should map regardless of case");
    }

    @Test
    void toPostgre_plainBooleanStillMapsToBoolean() {
        assertEquals("boolean", TypeConverter.toPostgre("BOOLEAN"),
                "A standalone routine's BOOLEAN keeps its existing mapping");
    }

    @Test
    void toPostgre_refCursorMapsToRefcursor() {
        assertEquals("refcursor", TypeConverter.toPostgre("REF CURSOR"),
                "An anonymous REF CURSOR reaches us under the dictionary's two-word spelling");
    }

    @Test
    void toPostgre_sysRefcursorStillMapsToRefcursor() {
        assertEquals("refcursor", TypeConverter.toPostgre("SYS_REFCURSOR"),
                "SYS_REFCURSOR keeps its existing mapping");
    }

    // PL/SQL TABLE and PL/SQL RECORD are deliberately *not* mapped. They are not built-ins: each
    // names a type declared inside a package, and placing one needs the metadata TypeResolver has
    // and TypeConverter does not. Mapping them to jsonb here would answer a question this class
    // cannot see the inputs to.
    @Test
    void toPostgre_plsqlRecordPassesThroughForTypeResolverToPlace() {
        assertEquals("pl/sql record", TypeConverter.toPostgre("PL/SQL RECORD"),
                "A package-declared type is not a built-in and must not be guessed at here");
    }

    // Built-ins written with a length that the prefix families (VARCHAR2, NUMBER, CHAR, ...) do not
    // cover (ROADMAP P1.9). PL/SQL code writes them in package variables, private signatures and
    // locals; unmapped, each blocked its whole package or reached PostgreSQL verbatim.
    @Test
    void toPostgreOrNull_varcharWithLengthIsVarchar2sSynonym() {
        assertEquals("varchar", TypeConverter.toPostgreOrNull("VARCHAR(20)"));
        assertEquals("varchar", TypeConverter.toPostgreOrNull("varchar(20 CHAR)"));
        assertEquals("varchar", TypeConverter.toPostgreOrNull("VARCHAR(20CHAR)"),
                "getText() on a type_spec drops the space between length and unit");
    }

    @Test
    void toPostgreOrNull_stringWithLengthIsVarchar() {
        assertEquals("varchar", TypeConverter.toPostgreOrNull("STRING(60)"));
    }

    @Test
    void toPostgreOrNull_integerWithPrecisionIsNumericNotInteger() {
        assertEquals("numeric", TypeConverter.toPostgreOrNull("INTEGER(11)"),
                "INTEGER(11) is NUMBER(11,0); eleven digits do not fit a PostgreSQL integer");
        assertEquals("numeric", TypeConverter.toPostgreOrNull("int(4)"));
        assertEquals("integer", TypeConverter.toPostgreOrNull("INTEGER"), "the unconstrained mapping is unchanged");
    }

    @Test
    void toPostgreOrNull_unlistedTypeWithLengthStaysUnrecognised() {
        assertNull(TypeConverter.toPostgreOrNull("FLOAT(126)"),
                "a length can change what a type is; an unlisted one is refused, not stripped");
        assertNull(TypeConverter.toPostgreOrNull("my_subtype(10)"));
    }
}
