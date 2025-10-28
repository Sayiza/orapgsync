package me.christianrobert.orapgsync.transformer.type.helpers;

import me.christianrobert.orapgsync.antlr.PlSqlParser.General_elementContext;
import me.christianrobert.orapgsync.antlr.PlSqlParser.General_element_partContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.TypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Static helper for resolving column types from metadata.
 *
 * <p>Handles column resolution from Phase 2:</p>
 * <ul>
 *   <li>Unqualified columns (emp_id)</li>
 *   <li>Qualified columns with table name (employees.emp_id)</li>
 *   <li>Qualified columns with alias (e.emp_id)</li>
 *   <li>Fully qualified (schema.table.column)</li>
 * </ul>
 *
 * <p>Pattern: Static helper following PostgresCodeBuilder architecture.</p>
 */
public final class ResolveColumn {

    private static final Logger log = LoggerFactory.getLogger(ResolveColumn.class);

    private ResolveColumn() {
        // Static utility class - prevent instantiation
    }

    /**
     * Resolves column type from metadata.
     *
     * <p>Handles both qualified and unqualified column references.</p>
     *
     * @param ctx General element context
     * @param currentSchema Current schema for lookups
     * @param indices Metadata indices
     * @param tableAliases Table alias map (alias -> table name)
     * @return TypeInfo from metadata, or UNKNOWN if not found
     */
    public static TypeInfo resolve(General_elementContext ctx,
                                    String currentSchema,
                                    TransformationIndices indices,
                                    Map<String, String> tableAliases) {
        if (ctx == null || ctx.general_element_part() == null || ctx.general_element_part().isEmpty()) {
            return TypeInfo.UNKNOWN;
        }

        int partCount = ctx.general_element_part().size();

        if (partCount == 1) {
            // Unqualified column: column_name
            String columnName = extractIdentifier(ctx.general_element_part().get(0));
            return resolveUnqualified(columnName, currentSchema, indices, tableAliases);

        } else if (partCount == 2) {
            // Qualified column: table.column or schema.table
            String qualifier = extractIdentifier(ctx.general_element_part().get(0));
            String columnName = extractIdentifier(ctx.general_element_part().get(1));
            return resolveQualified(qualifier, columnName, currentSchema, indices, tableAliases);

        } else if (partCount == 3) {
            // Fully qualified: schema.table.column
            String schema = extractIdentifier(ctx.general_element_part().get(0));
            String table = extractIdentifier(ctx.general_element_part().get(1));
            String column = extractIdentifier(ctx.general_element_part().get(2));
            return lookup(schema, table, column, indices);

        } else {
            // Too many parts - not a simple column reference
            log.trace("Column reference has {} parts, cannot resolve", partCount);
            return TypeInfo.UNKNOWN;
        }
    }

    /**
     * Extracts identifier text from general_element_part.
     */
    private static String extractIdentifier(General_element_partContext partCtx) {
        if (partCtx == null || partCtx.id_expression() == null) {
            return null;
        }
        String text = partCtx.id_expression().getText();
        return text != null ? text.toLowerCase() : null;
    }

    /**
     * Resolves unqualified column reference.
     *
     * <p>Tries to resolve from all tables in current FROM clause.</p>
     */
    private static TypeInfo resolveUnqualified(String columnName,
                                                String currentSchema,
                                                TransformationIndices indices,
                                                Map<String, String> tableAliases) {
        if (columnName == null) {
            return TypeInfo.UNKNOWN;
        }

        // Try each table in the current FROM clause
        for (String tableName : tableAliases.values()) {
            TypeInfo type = lookup(currentSchema, tableName, columnName, indices);
            if (!type.isUnknown()) {
                log.trace("Resolved unqualified column {} to type {} from table {}",
                    columnName, type.getCategory(), tableName);
                return type;
            }
        }

        log.trace("Could not resolve unqualified column: {}", columnName);
        return TypeInfo.UNKNOWN;
    }

    /**
     * Resolves qualified column reference: qualifier.column.
     *
     * <p>The qualifier could be a table name or alias.</p>
     */
    private static TypeInfo resolveQualified(String qualifier,
                                             String columnName,
                                             String currentSchema,
                                             TransformationIndices indices,
                                             Map<String, String> tableAliases) {
        if (qualifier == null || columnName == null) {
            return TypeInfo.UNKNOWN;
        }

        // Check if qualifier is a table alias
        String tableName = tableAliases.get(qualifier);
        if (tableName != null) {
            // Alias found - lookup column in that table
            TypeInfo type = lookup(currentSchema, tableName, columnName, indices);
            if (!type.isUnknown()) {
                log.trace("Resolved qualified column {}.{} (alias) to type {}",
                    qualifier, columnName, type.getCategory());
                return type;
            }
        }

        // Not an alias - try as direct table name
        TypeInfo type = lookup(currentSchema, qualifier, columnName, indices);
        if (!type.isUnknown()) {
            log.trace("Resolved qualified column {}.{} (table) to type {}",
                qualifier, columnName, type.getCategory());
            return type;
        }

        log.trace("Could not resolve qualified column: {}.{}", qualifier, columnName);
        return TypeInfo.UNKNOWN;
    }

    /**
     * Looks up column type from metadata indices.
     *
     * @param schema Schema name (normalized lowercase)
     * @param table Table name (normalized lowercase)
     * @param column Column name (normalized lowercase)
     * @param indices Transformation indices
     * @return TypeInfo from metadata, or UNKNOWN if not found
     */
    private static TypeInfo lookup(String schema,
                                    String table,
                                    String column,
                                    TransformationIndices indices) {
        if (schema == null || table == null || column == null) {
            return TypeInfo.UNKNOWN;
        }

        // Build lookup key: schema.table
        String tableKey = schema + "." + table;

        // Get column metadata from indices
        TransformationIndices.ColumnTypeInfo columnInfo = indices.getColumnType(tableKey, column);
        if (columnInfo == null) {
            log.trace("Column {} not found in table {}", column, tableKey);
            return TypeInfo.UNKNOWN;
        }

        // Map Oracle type to TypeInfo
        String oracleType = columnInfo.getTypeName();
        TypeInfo type = mapOracleType(oracleType);
        log.trace("Mapped column {}.{}.{} type {} to {}",
            schema, table, column, oracleType, type.getCategory());
        return type;
    }

    /**
     * Maps Oracle type string to TypeInfo.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>NUMBER → NUMERIC</li>
     *   <li>VARCHAR2 → TEXT</li>
     *   <li>DATE → DATE</li>
     *   <li>TIMESTAMP → TIMESTAMP</li>
     * </ul>
     */
    private static TypeInfo mapOracleType(String oracleType) {
        if (oracleType == null) {
            return TypeInfo.UNKNOWN;
        }

        // Normalize and extract base type (before parentheses)
        String normalized = oracleType.toUpperCase().trim();
        String baseType = normalized.split("\\(")[0].trim();

        // Map to TypeInfo categories
        switch (baseType) {
            case "NUMBER":
            case "INTEGER":
            case "FLOAT":
            case "BINARY_FLOAT":
            case "BINARY_DOUBLE":
                return TypeInfo.NUMERIC;

            case "VARCHAR2":
            case "VARCHAR":
            case "CHAR":
            case "NCHAR":
            case "NVARCHAR2":
            case "CLOB":
            case "NCLOB":
                return TypeInfo.TEXT;

            case "DATE":
                return TypeInfo.DATE;

            case "TIMESTAMP":
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                return TypeInfo.TIMESTAMP;

            case "BOOLEAN":
                return TypeInfo.BOOLEAN;

            default:
                // Unknown type (could be user-defined type, BLOB, etc.)
                log.trace("Unknown Oracle type: {}", oracleType);
                return TypeInfo.UNKNOWN;
        }
    }
}
