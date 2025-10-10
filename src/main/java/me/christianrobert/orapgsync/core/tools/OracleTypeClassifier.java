package me.christianrobert.orapgsync.core.tools;

/**
 * Utility class for classifying Oracle data types into categories for PostgreSQL migration.
 *
 * <h2>Type Classification Strategy</h2>
 * Oracle data types are classified into three categories:
 *
 * <h3>1. Built-in Oracle Types</h3>
 * Standard Oracle types like NUMBER, VARCHAR2, DATE, TIMESTAMP, etc.
 * <ul>
 *   <li>Handled by: {@link TypeConverter#toPostgre(String)}</li>
 *   <li>Migration: Direct type mapping to PostgreSQL equivalents</li>
 *   <li>Identification: No owner specified (dataTypeOwner is null)</li>
 * </ul>
 *
 * <h3>2. User-Defined Object Types (Composite Types)</h3>
 * Custom types created by users (e.g., ADDRESS_TYPE, CUSTOMER_TYPE).
 * <ul>
 *   <li>Table Creation: Use PostgreSQL composite type {@code schema.typename}</li>
 *   <li>Object Type Creation: Reference as {@code schema.typename}</li>
 *   <li>Data Transfer: Direct structural mapping (Oracle object → PostgreSQL composite)</li>
 *   <li>Identification: Has owner that is NOT a system schema (sys/public)</li>
 * </ul>
 *
 * <h3>3. Complex Oracle System Types (jsonb Serialization)</h3>
 * Oracle system types that cannot be directly mapped to PostgreSQL.
 * <ul>
 *   <li>Examples: SYS.ANYDATA, SYS.ANYTYPE, SYS.AQ$_*, PUBLIC.ANYDATA, SDO_GEOMETRY</li>
 *   <li>Table Creation: Create as {@code jsonb} column in PostgreSQL</li>
 *   <li>Object Type Creation: Use {@code jsonb} for attributes with these types</li>
 *   <li>Data Transfer: Serialize to JSON with type metadata wrapper:
 *     <pre>{"oracleType": "SYS.ANYDATA", "value": {...}}</pre>
 *   </li>
 *   <li>Identification: Owner is "sys" or "public" AND type matches known system types</li>
 *   <li>Note: PUBLIC owner often indicates Oracle PUBLIC synonyms/grants for SYS types</li>
 * </ul>
 *
 * <h2>Benefits of Classification</h2>
 * <ul>
 *   <li>Preserves original Oracle type metadata for future PL/SQL code conversion</li>
 *   <li>Enables CSV batch transfer (jsonb serialization maintains performance)</li>
 *   <li>PostgreSQL jsonb operators allow type-aware queries</li>
 *   <li>Debuggable and inspectable data</li>
 * </ul>
 *
 * @see TypeConverter
 * @see me.christianrobert.orapgsync.table.job.PostgresTableCreationJob
 * @see me.christianrobert.orapgsync.objectdatatype.job.PostgresObjectTypeCreationJob
 * @see me.christianrobert.orapgsync.transfer.service.OracleComplexTypeSerializer
 */
public class OracleTypeClassifier {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private OracleTypeClassifier() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Identifies Oracle XMLTYPE, which has a direct mapping to PostgreSQL's native xml type.
     * Despite being a SYS-owned type, XMLTYPE is treated as a mappable built-in type
     * because PostgreSQL has native XML support.
     *
     * <p>Note: This is separate from {@link #isComplexOracleSystemType(String, String)}
     * because XMLTYPE doesn't require jsonb serialization - it has a direct PostgreSQL equivalent.</p>
     *
     * @param owner The schema/owner of the type (e.g., "sys", "public", "myschema").
     *              Should be lowercase for consistency.
     * @param type The type name (e.g., "xmltype").
     *             Should be lowercase for consistency.
     * @return {@code true} if this is Oracle XMLTYPE (SYS.XMLTYPE or PUBLIC.XMLTYPE),
     *         {@code false} otherwise
     */
    public static boolean isXmlType(String owner, String type) {
        if (owner == null || type == null) {
            return false;
        }

        // Normalize to lowercase for case-insensitive comparison
        String normalizedOwner = owner.toLowerCase();
        String normalizedType = type.toLowerCase();

        // XMLTYPE can appear as SYS.XMLTYPE or PUBLIC.XMLTYPE (via PUBLIC synonym)
        return ("sys".equals(normalizedOwner) || "public".equals(normalizedOwner))
                && "xmltype".equals(normalizedType);
    }

    /**
     * Identifies complex Oracle system types that cannot be directly mapped to PostgreSQL composite types.
     * These types will be stored as jsonb with metadata preservation during data transfer.
     *
     * <p>System-owned complex types that need jsonb serialization include:</p>
     * <ul>
     *   <li>Oracle Advanced Queuing types: AQ$_* (e.g., AQ$_JMS_TEXT_MESSAGE)</li>
     *   <li>Oracle dynamic types: ANYDATA, ANYTYPE</li>
     *   <li>Spatial/geometry types: SDO_GEOMETRY</li>
     * </ul>
     *
     * <p><strong>Note:</strong> XMLTYPE is NOT included here because it has a direct mapping
     * to PostgreSQL's native {@code xml} type. Use {@link #isXmlType(String, String)} to check for XMLTYPE.</p>
     *
     * <p>Oracle databases often have PUBLIC synonyms for SYS types (grants to PUBLIC),
     * so we check both "sys" and "public" as indicators of Oracle system types.</p>
     *
     * @param owner The schema/owner of the type (e.g., "sys", "public", "myschema").
     *              Should be lowercase for consistency.
     * @param type The type name (e.g., "anydata", "aq$_jms_text_message").
     *             Should be lowercase for consistency.
     * @return {@code true} if this is a complex Oracle system type requiring jsonb serialization,
     *         {@code false} if it's a user-defined type or should be handled differently
     */
    public static boolean isComplexOracleSystemType(String owner, String type) {
        if (owner == null || type == null) {
            return false;
        }

        // Normalize to lowercase for case-insensitive comparison
        String normalizedOwner = owner.toLowerCase();
        String normalizedType = type.toLowerCase();

        // System-owned complex types that need jsonb serialization
        // Note: Oracle databases often have PUBLIC synonyms for SYS types (grants to PUBLIC)
        // so we check both "sys" and "public" as indicators of Oracle system types
        if ("sys".equals(normalizedOwner) || "public".equals(normalizedOwner)) {
            // Oracle Advanced Queuing types
            if (normalizedType.startsWith("aq$_")) {
                return true;
            }
            // Oracle dynamic types
            if (normalizedType.equals("anydata") || normalizedType.equals("anytype")) {
                return true;
            }
            // Spatial/geometry types
            if (normalizedType.equals("sdo_geometry")) {
                return true;
            }
        }

        // All other custom types are assumed to be user-defined composite types
        // that have been created in PostgreSQL via ObjectTypeCreationJob
        return false;
    }

    /**
     * Convenience method that checks if a type with an owner is a complex Oracle system type.
     *
     * @param dataTypeOwner The owner/schema of the type (can be null for built-in types)
     * @param dataType The type name
     * @return {@code true} if this is a complex Oracle system type, {@code false} otherwise
     */
    public static boolean isComplexOracleSystemType(String dataTypeOwner, String dataType, boolean hasOwner) {
        if (!hasOwner || dataTypeOwner == null) {
            return false; // Built-in types don't have owners
        }
        return isComplexOracleSystemType(dataTypeOwner, dataType);
    }
}
