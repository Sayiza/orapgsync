package me.christianrobert.orapgsync.transformer.builder.objectfield;

import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;

/**
 * Transforms Oracle object type field access to PostgreSQL composite type syntax.
 *
 * <p><strong>Transformation Pattern:</strong></p>
 * <ul>
 *   <li>Oracle:  {@code table.column.field}  →  PostgreSQL: {@code (table.column).field}</li>
 *   <li>Oracle:  {@code table.column.field1.field2}  →  PostgreSQL: {@code ((table.column).field1).field2}</li>
 * </ul>
 *
 * <p><strong>Maximum nesting depth:</strong> 2 levels (sufficient for 99% of real-world cases)</p>
 *
 * <p><strong>Key features:</strong></p>
 * <ul>
 *   <li>Metadata-driven detection (no heuristics)</li>
 *   <li>Synonym-aware (resolves types via current schema → PUBLIC → SYS)</li>
 *   <li>Pass-through for unknown fields (preserves Oracle intent, PostgreSQL reports error)</li>
 *   <li>Works in all SQL clauses (SELECT, WHERE, ORDER BY, JOIN conditions)</li>
 * </ul>
 *
 * @see me.christianrobert.orapgsync.documentation.OBJECT_TYPE_FIELD_ACCESS_IMPLEMENTATION_PLAN
 */
public class ObjectFieldAccessTransformer {

    private final TransformationContext context;

    public ObjectFieldAccessTransformer(TransformationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("TransformationContext cannot be null");
        }
        this.context = context;
    }

    /**
     * Attempts to transform a dot-separated identifier chain as object field access.
     *
     * <p>Returns null if this is not an object field access (e.g., it's a package function,
     * type method, or simple column reference).</p>
     *
     * @param identifierChain Full chain (e.g., "l.langy.de" or "hr.employees.address.street")
     * @return Transformed SQL with parentheses, or null if no transformation needed
     */
    public String transform(String identifierChain) {
        if (identifierChain == null || identifierChain.trim().isEmpty()) {
            return null;
        }

        // STEP 1: Parse and validate
        String[] parts = identifierChain.split("\\.");
        if (parts.length < 3) {
            return null;  // Need at least alias.column.field
        }

        // STEP 2: Resolve table/alias (determine starting point)
        TableResolution tableRes = resolveTableAndStartIndex(parts);
        if (tableRes == null) {
            return null;  // Not a table reference - delegate to existing logic
        }

        // STEP 3: Get column type and qualify it
        if (tableRes.startIndex >= parts.length) {
            return null;  // No parts after table
        }

        String columnName = parts[tableRes.startIndex];
        TransformationIndices.ColumnTypeInfo columnTypeInfo =
                context.getColumnType(tableRes.qualifiedTable, columnName);

        if (columnTypeInfo == null) {
            return null;  // Unknown column
        }

        // Qualify the type name using synonym resolution
        String columnType = columnTypeInfo.getTypeName();
        String qualifiedColumnType = context.qualifyTypeName(columnType);

        if (!context.isObjectType(qualifiedColumnType)) {
            return null;  // Not an object type, no field access possible
        }

        // STEP 4: Check if there's actually field access
        if (tableRes.startIndex + 1 >= parts.length) {
            return null;  // Just selecting object column, no field access
        }

        // STEP 5-6: Transform field access (1-2 levels)
        return transformFieldAccess(parts, tableRes.startIndex, qualifiedColumnType);
    }

    /**
     * Resolves the table/alias from the identifier chain.
     *
     * <p>Tries in order:</p>
     * <ol>
     *   <li>Table alias in current query</li>
     *   <li>Synonym (could point to table)</li>
     *   <li>Schema.Table pattern</li>
     *   <li>Unqualified table in current schema</li>
     * </ol>
     *
     * @param parts Identifier chain parts
     * @return TableResolution with table name and start index, or null if not a table reference
     */
    private TableResolution resolveTableAndStartIndex(String[] parts) {
        // Option A: Table alias in current query
        String aliasResolved = context.resolveAlias(parts[0]);
        if (aliasResolved != null) {
            // Alias resolved to qualified table name
            String qualifiedTable = aliasResolved;
            if (!qualifiedTable.contains(".")) {
                // Add schema if not already qualified
                qualifiedTable = context.getCurrentSchema().toLowerCase() + "." + qualifiedTable.toLowerCase();
            }
            return new TableResolution(qualifiedTable.toLowerCase(), 1);
        }

        // Option B: Synonym (could point to table)
        String synonymResolved = context.resolveSynonym(parts[0]);
        if (synonymResolved != null) {
            return new TableResolution(synonymResolved.toLowerCase(), 1);
        }

        // Option C: Schema.Table pattern
        if (parts.length >= 2) {
            String possibleSchema = parts[0];
            String possibleTable = parts[1];
            String qualifiedTable = (possibleSchema + "." + possibleTable).toLowerCase();

            // Check if this table exists
            if (isTableInSchema(qualifiedTable)) {
                return new TableResolution(qualifiedTable, 2);
            }
        }

        // Option D: Unqualified table in current schema
        String qualifiedTable = (context.getCurrentSchema() + "." + parts[0]).toLowerCase();
        if (isTableInSchema(qualifiedTable)) {
            return new TableResolution(qualifiedTable, 1);
        }

        // Not a table reference
        return null;
    }

    /**
     * Checks if a qualified table name exists in metadata.
     *
     * @param qualifiedTable Table name in "schema.table" format
     * @return true if table exists
     */
    private boolean isTableInSchema(String qualifiedTable) {
        // We can check this by trying to get any column from the table
        // If getColumnType returns non-null for any check, the table exists
        // For efficiency, we rely on the fact that tables in indices have columns
        // This is a simple existence check - we don't need to iterate all columns
        return context.getIndices().getAllTableColumns().containsKey(qualifiedTable.toLowerCase());
    }

    /**
     * Transforms field access with 1-2 levels of nesting.
     *
     * @param parts Full identifier chain parts
     * @param startIndex Index where column name starts
     * @param qualifiedColumnType Qualified type of the column
     * @return Transformed SQL with parentheses
     */
    private String transformFieldAccess(String[] parts, int startIndex, String qualifiedColumnType) {
        // STEP 5: Transform first-level field access

        // Rebuild the base (everything up to and including column)
        StringBuilder base = new StringBuilder();
        for (int i = 0; i < startIndex + 1; i++) {
            if (i > 0) {
                base.append(".");
            }
            base.append(parts[i]);
        }

        String field1Name = parts[startIndex + 1];
        String field1Type = context.getFieldType(qualifiedColumnType, field1Name);

        if (field1Type == null) {
            // Unknown field - pass through (let PostgreSQL report error)
            return null;
        }

        // Apply PostgreSQL transformation
        String result = "(" + base + ")." + field1Name;

        // Check if there are more parts (nested object case)
        if (startIndex + 2 >= parts.length) {
            return result;  // Done! e.g., "(l.langy).de"
        }

        // STEP 6: Handle nested object field access (level 2)

        // Qualify field1 type
        String qualifiedField1Type = context.qualifyTypeName(field1Type);

        if (!context.isObjectType(qualifiedField1Type)) {
            // field1 is primitive, but more parts exist - unusual case
            // Pass through remaining parts (will likely error, but preserve intent)
            for (int i = startIndex + 2; i < parts.length; i++) {
                result += "." + parts[i];
            }
            return result;
        }

        // field1 is also an object type, access its field
        String field2Name = parts[startIndex + 2];
        String field2Type = context.getFieldType(qualifiedField1Type, field2Name);

        if (field2Type == null) {
            // Unknown nested field - pass through
            return result + "." + field2Name;
        }

        // Apply nested transformation
        result = "(" + result + ")." + field2Name;

        // If more parts exist beyond our max depth (2 levels), append them
        if (startIndex + 3 < parts.length) {
            for (int i = startIndex + 3; i < parts.length; i++) {
                result += "." + parts[i];
            }
            // Note: This might cause PostgreSQL errors if parts[i] references deeper nesting
            // But we preserve Oracle's structure
        }

        return result;
    }

    /**
     * Table resolution result.
     */
    private static class TableResolution {
        final String qualifiedTable;  // e.g., "hr.langtable"
        final int startIndex;          // Index where column name starts

        TableResolution(String qualifiedTable, int startIndex) {
            this.qualifiedTable = qualifiedTable;
            this.startIndex = startIndex;
        }
    }
}
