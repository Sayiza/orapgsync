package me.christianrobert.orapgsync.transformation.context;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides global context for transformation process.
 *
 * <p>Contains:
 * <ul>
 *   <li>Pre-built metadata indices for fast lookups</li>
 *   <li>Current schema context</li>
 *   <li>Query-local state (table aliases)</li>
 * </ul>
 *
 * <p>This context is passed to every semantic node's {@code toPostgres()} method,
 * providing all the information needed to transform Oracle SQL to PostgreSQL.
 */
public class TransformationContext {

    private final String currentSchema;
    private final TransformationIndices indices;

    // Query-local state (mutable, reset per query)
    private final Map<String, String> tableAliases;  // alias → table name

    /**
     * Creates context with metadata indices.
     *
     * @param currentSchema Schema context for resolution
     * @param indices Pre-built metadata indices
     */
    public TransformationContext(String currentSchema, TransformationIndices indices) {
        if (currentSchema == null || currentSchema.trim().isEmpty()) {
            throw new IllegalArgumentException("Current schema cannot be null or empty");
        }
        if (indices == null) {
            throw new IllegalArgumentException("Indices cannot be null");
        }

        this.currentSchema = currentSchema;
        this.indices = indices;
        this.tableAliases = new HashMap<>();
    }

    public String getCurrentSchema() {
        return currentSchema;
    }

    public TransformationIndices getIndices() {
        return indices;
    }

    // ========== Synonym Resolution ==========

    /**
     * Resolves a name that might be a synonym.
     * Follows Oracle resolution rules: current schema → PUBLIC fallback.
     *
     * @param name Name to resolve (table, view, etc.)
     * @return Qualified target name "schema.table" or null if not a synonym
     */
    public String resolveSynonym(String name) {
        return indices.resolveSynonym(currentSchema, name);
    }

    // ========== Type Metadata ==========

    /**
     * Gets type information for a column.
     *
     * @param qualifiedTable Table name in "schema.table" format
     * @param columnName Column name
     * @return ColumnTypeInfo or null if not found
     */
    public TransformationIndices.ColumnTypeInfo getColumnType(String qualifiedTable, String columnName) {
        return indices.getColumnType(qualifiedTable, columnName);
    }

    /**
     * Checks if a type has a specific method (for disambiguation).
     *
     * @param qualifiedType Type name in "schema.typename" format
     * @param methodName Method name
     * @return true if the type has this method
     */
    public boolean hasTypeMethod(String qualifiedType, String methodName) {
        return indices.hasTypeMethod(qualifiedType, methodName);
    }

    /**
     * Checks if a qualified name is a package function (for disambiguation).
     *
     * @param qualifiedName Function name in "schema.package.function" format
     * @return true if this is a known package function
     */
    public boolean isPackageFunction(String qualifiedName) {
        return indices.isPackageFunction(qualifiedName);
    }

    // ========== Query-Local State (Table Aliases) ==========

    /**
     * Registers a table alias for the current query.
     *
     * @param alias Alias name
     * @param tableName Actual table name
     */
    public void registerAlias(String alias, String tableName) {
        if (alias != null && tableName != null) {
            tableAliases.put(alias.toLowerCase(), tableName.toLowerCase());
        }
    }

    /**
     * Resolves a table alias to the actual table name.
     *
     * @param alias Alias to resolve
     * @return Table name or null if not found
     */
    public String resolveAlias(String alias) {
        if (alias == null) {
            return null;
        }
        return tableAliases.get(alias.toLowerCase());
    }

    /**
     * Clears all registered aliases (for starting a new query).
     */
    public void clearAliases() {
        tableAliases.clear();
    }

    // ========== Type Conversion (Future) ==========

    /**
     * Converts Oracle type to PostgreSQL equivalent.
     * TODO: Implement full type conversion in future phase.
     *
     * @param oracleType Oracle type name
     * @return PostgreSQL type name
     */
    public String convertType(String oracleType) {
        // TODO: Implement type conversion
        // For now, pass through as-is
        return oracleType;
    }
}
