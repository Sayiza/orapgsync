package me.christianrobert.orapgsync.transformer.context;

import me.christianrobert.orapgsync.transformer.inline.ConversionStrategy;
import me.christianrobert.orapgsync.transformer.inline.InlineTypeDefinition;
import me.christianrobert.orapgsync.transformer.inline.TypeCategory;

import java.util.Map;

/**
 * The types that Oracle's own packages declare, for the packages the compatibility layer stands in
 * for (ROADMAP P1.7).
 *
 * <p>{@code OWA.VC_ARR} is an ordinary package type: Oracle's {@code OWA} spec declares it as
 * {@code TABLE OF VARCHAR2(32000) INDEX BY BINARY_INTEGER}. A user package's collection becomes
 * {@code jsonb}, and so does this one. The only difference is where the declaration comes from.
 * Oracle's packages are not extracted (they are not migrated, and are not in the user's schemas),
 * so their declarations are written down here, from Oracle's documented specs.
 *
 * <p>These types used to be flattened to {@code oracle_compat.owa__vc_arr}, which nothing creates:
 * the compatibility layer installs functions, never types. That was an answer without positive
 * metadata behind it. It reached PostgreSQL as {@code schema "oracle_compat" does not exist} at
 * the stub step, and blocked every member of a package that held such a variable.
 *
 * <p>A type that is not listed here is not guessed at. {@link TransformationContext} answers it
 * like any other undeclared package type: the visitor layer gets its {@code jsonb} fallback, and
 * a signature refuses it by name.
 */
final class OracleBuiltinPackageTypes {

    private OracleBuiltinPackageTypes() {
    }

    /**
     * Keyed by {@code PACKAGE.TYPE}, upper-case. All but one are associative arrays indexed by
     * {@code BINARY_INTEGER}.
     */
    private static final Map<String, String> INDEX_BY_ELEMENT_TYPES = Map.ofEntries(
            // Flexible-parameter entry points: name_array / value_array.
            Map.entry("OWA.VC_ARR", "VARCHAR2(32000)"),
            Map.entry("OWA.NC_ARR", "NVARCHAR2"),
            Map.entry("OWA.RAW_ARR", "RAW"),
            Map.entry("OWA_UTIL.IP_ADDRESS", "INTEGER"),
            Map.entry("OWA_UTIL.IDENT_ARR", "VARCHAR2(30)"),
            Map.entry("OWA_UTIL.NUM_ARR", "NUMBER"),
            Map.entry("OWA_COOKIE.VC_ARR", "VARCHAR2(4000)"),
            Map.entry("HTP.HTBUF_ARR", "VARCHAR2(256)"),
            Map.entry("DBMS_OUTPUT.CHARARR", "VARCHAR2(32767)"),
            Map.entry("DBMS_UTILITY.UNCL_ARRAY", "VARCHAR2(227)"),
            Map.entry("DBMS_UTILITY.NAME_ARRAY", "VARCHAR2(30)"),
            Map.entry("DBMS_UTILITY.LNAME_ARRAY", "VARCHAR2(4000)"),
            Map.entry("DBMS_UTILITY.DBLINK_ARRAY", "VARCHAR2(128)"),
            Map.entry("DBMS_UTILITY.NUMBER_ARRAY", "NUMBER"),
            Map.entry("DBMS_UTILITY.INDEX_TABLE_TYPE", "BINARY_INTEGER"));

    /**
     * Resolves a type declared by one of Oracle's packages.
     *
     * <p>{@code UTL_FILE.FILE_TYPE} is the exception to "a structure becomes jsonb". In Oracle it is
     * a record whose fields no caller reads; it is the handle {@code FOPEN} returns and every other
     * call takes. The compatibility layer's {@code utl_file__fopen} returns that handle as
     * {@code integer}, so the variable has to be one, or the assignment from it fails.
     *
     * @param packageName the package as written, any case
     * @param typeName    the type as written, any case
     * @return the resolution, or null if the type is not one of the listed declarations
     */
    static TransformationContext.PackageTypeResolution resolve(String packageName, String typeName) {
        String key = (packageName + "." + typeName).toUpperCase();
        if (key.equals("UTL_FILE.FILE_TYPE")) {
            return TransformationContext.PackageTypeResolution.ofType("integer");
        }
        String elementType = INDEX_BY_ELEMENT_TYPES.get(key);
        if (elementType == null) {
            return null;
        }
        return TransformationContext.PackageTypeResolution.ofInlineType(new InlineTypeDefinition(
                typeName.toLowerCase(), TypeCategory.INDEX_BY, elementType, null,
                ConversionStrategy.JSONB, null, "BINARY_INTEGER"));
    }
}
