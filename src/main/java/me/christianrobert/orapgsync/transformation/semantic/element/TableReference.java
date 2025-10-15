package me.christianrobert.orapgsync.transformation.semantic.element;

import me.christianrobert.orapgsync.transformation.context.TransformationContext;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;

/**
 * Represents a table reference in a FROM clause.
 * In this minimal implementation, handles simple table names without joins or aliases.
 */
public class TableReference implements SemanticNode {

    private final String tableName;
    private final String alias;

    public TableReference(String tableName) {
        this(tableName, null);
    }

    public TableReference(String tableName, String alias) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        this.tableName = tableName;
        this.alias = alias;
    }

    public String getTableName() {
        return tableName;
    }

    public String getAlias() {
        return alias;
    }

    public boolean hasAlias() {
        return alias != null && !alias.trim().isEmpty();
    }

    @Override
    public String toPostgres(TransformationContext context) {
        // In this minimal implementation, just pass through the table name
        // Future phases will add:
        // - Schema qualification
        // - Synonym resolution
        // - Alias handling
        if (hasAlias()) {
            return tableName + " " + alias;
        }
        return tableName;
    }

    @Override
    public String toString() {
        if (hasAlias()) {
            return "TableReference{table='" + tableName + "', alias='" + alias + "'}";
        }
        return "TableReference{table='" + tableName + "'}";
    }
}
