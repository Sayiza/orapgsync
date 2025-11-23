package me.christianrobert.orapgsync.transformer.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pre-built metadata lookup indices for fast O(1) queries during transformation.
 *
 * <p>These indices are built once per transformation session from StateService metadata
 * and provide fast lookups for:
 * <ul>
 *   <li>Table columns and their types</li>
 *   <li>Type methods (for disambiguating type.method() vs package.function())</li>
 *   <li>Package functions</li>
 *   <li>Synonym resolution</li>
 * </ul>
 *
 * <p>This is a pure data structure with no service dependencies, making it:
 * <ul>
 *   <li>Easy to test (can create minimal test indices)</li>
 *   <li>Efficient (hash map lookups are O(1))</li>
 *   <li>Immutable (thread-safe, can be reused)</li>
 * </ul>
 *
 * <p>Built by {@link MetadataIndexBuilder}
 * from {@link me.christianrobert.orapgsync.core.service.StateService} data.
 */
public class TransformationIndices {

    // Table → Column → Type mapping
    // Key: "schema.table" (lowercase), Value: Map of column name → type info
    private final Map<String, Map<String, ColumnTypeInfo>> tableColumns;

    // Type → Method names mapping
    // Key: "schema.typename" (lowercase), Value: Set of method names (lowercase)
    private final Map<String, Set<String>> typeMethods;

    // Package function qualified names
    // Set of "schema.package.function" (lowercase)
    private final Set<String> packageFunctions;

    // Synonym resolution
    // Key: "schema" (lowercase), Value: Map of synonym name → "target_schema.target_table"
    private final Map<String, Map<String, String>> synonyms;

    // Object type field definitions
    // Key: QualifiedTypeName (e.g., "hr.address_type") → (FieldName → FieldType)
    // Example: "hr.address_type" → {"street" → "VARCHAR2", "city" → "VARCHAR2"}
    private final Map<String, Map<String, String>> typeFieldTypes;

    // Object type names (for quick existence checks)
    // Set of qualified type names (e.g., "hr.address_type", "public.langy_type")
    private final Set<String> objectTypeNames;

    /**
     * Creates indices with all lookup maps.
     * Maps are defensively copied to ensure immutability.
     */
    public TransformationIndices(
            Map<String, Map<String, ColumnTypeInfo>> tableColumns,
            Map<String, Set<String>> typeMethods,
            Set<String> packageFunctions,
            Map<String, Map<String, String>> synonyms,
            Map<String, Map<String, String>> typeFieldTypes,
            Set<String> objectTypeNames) {

        this.tableColumns = deepCopyTableColumns(tableColumns);
        this.typeMethods = deepCopyTypeMethods(typeMethods);
        this.packageFunctions = Set.copyOf(packageFunctions);
        this.synonyms = deepCopySynonyms(synonyms);
        this.typeFieldTypes = deepCopyTypeFields(typeFieldTypes);
        this.objectTypeNames = Set.copyOf(objectTypeNames);
    }

    /**
     * Gets the type of a column in a table.
     *
     * @param qualifiedTable Table name in "schema.table" format (case-insensitive)
     * @param columnName Column name (case-insensitive)
     * @return ColumnTypeInfo or null if not found
     */
    public ColumnTypeInfo getColumnType(String qualifiedTable, String columnName) {
        if (qualifiedTable == null || columnName == null) {
            return null;
        }

        String normalizedTable = qualifiedTable.toLowerCase();
        String normalizedColumn = columnName.toLowerCase();

        Map<String, ColumnTypeInfo> columns = tableColumns.get(normalizedTable);
        if (columns == null) {
            return null;
        }

        return columns.get(normalizedColumn);
    }

    /**
     * Checks if a type has a specific method.
     *
     * @param qualifiedType Type name in "schema.typename" format (case-insensitive)
     * @param methodName Method name (case-insensitive)
     * @return true if the type has this method
     */
    public boolean hasTypeMethod(String qualifiedType, String methodName) {
        if (qualifiedType == null || methodName == null) {
            return false;
        }

        String normalizedType = qualifiedType.toLowerCase();
        String normalizedMethod = methodName.toLowerCase();

        Set<String> methods = typeMethods.get(normalizedType);
        if (methods == null) {
            return false;
        }

        return methods.contains(normalizedMethod);
    }

    /**
     * Checks if a qualified name is a package function.
     *
     * @param qualifiedName Function name in "schema.package.function" format (case-insensitive)
     * @return true if this is a known package function
     */
    public boolean isPackageFunction(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }

        return packageFunctions.contains(qualifiedName.toLowerCase());
    }

    /**
     * Resolves a synonym to its target.
     * Follows Oracle resolution rules: current schema → PUBLIC fallback.
     *
     * @param currentSchema Current schema context
     * @param synonymName Synonym name
     * @return Qualified target name "schema.table" or null if not found
     */
    public String resolveSynonym(String currentSchema, String synonymName) {
        if (currentSchema == null || synonymName == null) {
            return null;
        }

        String normalizedSchema = currentSchema.toLowerCase();
        String normalizedSynonym = synonymName.toLowerCase();

        // Check current schema first
        Map<String, String> currentSchemaSynonyms = synonyms.get(normalizedSchema);
        if (currentSchemaSynonyms != null) {
            String target = currentSchemaSynonyms.get(normalizedSynonym);
            if (target != null) {
                return target;
            }
        }

        // Fall back to PUBLIC schema
        Map<String, String> publicSynonyms = synonyms.get("public");
        if (publicSynonyms != null) {
            return publicSynonyms.get(normalizedSynonym);
        }

        return null;
    }

    /**
     * Gets all table column mappings (for testing/debugging).
     */
    public Map<String, Map<String, ColumnTypeInfo>> getAllTableColumns() {
        return Collections.unmodifiableMap(tableColumns);
    }

    /**
     * Gets all type method mappings (for testing/debugging).
     */
    public Map<String, Set<String>> getAllTypeMethods() {
        return Collections.unmodifiableMap(typeMethods);
    }

    /**
     * Gets all package functions (for testing/debugging).
     */
    public Set<String> getAllPackageFunctions() {
        return Collections.unmodifiableSet(packageFunctions);
    }

    /**
     * Get the type of a field in an object type.
     *
     * @param qualifiedTypeName Schema-qualified type name (e.g., "hr.address_type", case-insensitive)
     * @param fieldName Field name (case-insensitive)
     * @return Field type (unqualified), or null if not found
     */
    public String getFieldType(String qualifiedTypeName, String fieldName) {
        if (qualifiedTypeName == null || fieldName == null) {
            return null;
        }

        String normalizedType = qualifiedTypeName.toLowerCase();
        String normalizedField = fieldName.toLowerCase();

        Map<String, String> fields = typeFieldTypes.get(normalizedType);
        if (fields == null) {
            return null;
        }

        return fields.get(normalizedField);
    }

    /**
     * Check if a type name is a known object type.
     *
     * @param qualifiedTypeName Schema-qualified type name (case-insensitive)
     * @return true if type exists in metadata
     */
    public boolean isObjectType(String qualifiedTypeName) {
        if (qualifiedTypeName == null) {
            return false;
        }

        return objectTypeNames.contains(qualifiedTypeName.toLowerCase());
    }

    /**
     * Get all fields of an object type.
     *
     * @param qualifiedTypeName Schema-qualified type name (case-insensitive)
     * @return Map of field names to types, or empty map if not found
     */
    public Map<String, String> getTypeFields(String qualifiedTypeName) {
        if (qualifiedTypeName == null) {
            return Collections.emptyMap();
        }

        Map<String, String> fields = typeFieldTypes.get(qualifiedTypeName.toLowerCase());
        if (fields == null) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(fields);
    }

    /**
     * Gets all type field mappings (for testing/debugging).
     */
    public Map<String, Map<String, String>> getAllTypeFields() {
        return Collections.unmodifiableMap(typeFieldTypes);
    }

    /**
     * Gets all object type names (for testing/debugging).
     */
    public Set<String> getAllObjectTypeNames() {
        return Collections.unmodifiableSet(objectTypeNames);
    }

    // Deep copy helpers to ensure immutability

    private Map<String, Map<String, ColumnTypeInfo>> deepCopyTableColumns(
            Map<String, Map<String, ColumnTypeInfo>> source) {
        Map<String, Map<String, ColumnTypeInfo>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, ColumnTypeInfo>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, Set<String>> deepCopyTypeMethods(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, Map<String, String>> deepCopySynonyms(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, Map<String, String>> deepCopyTypeFields(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Column type information for metadata lookups.
     */
    public static class ColumnTypeInfo {
        private final String typeName;
        private final String typeOwner;

        public ColumnTypeInfo(String typeName, String typeOwner) {
            this.typeName = typeName;
            this.typeOwner = typeOwner;
        }

        public String getTypeName() {
            return typeName;
        }

        public String getTypeOwner() {
            return typeOwner;
        }

        public String getQualifiedType() {
            if (typeOwner != null && !typeOwner.isEmpty()) {
                return typeOwner + "." + typeName;
            }
            return typeName;
        }

        /**
         * Checks if this is a custom (user-defined) type.
         * Built-in Oracle types have null typeOwner.
         *
         * @return true if this is a custom type with an owner/schema
         */
        public boolean isCustomType() {
            return typeOwner != null && !typeOwner.isEmpty();
        }

        @Override
        public String toString() {
            return "ColumnTypeInfo{type='" + getQualifiedType() + "'}";
        }
    }
}
