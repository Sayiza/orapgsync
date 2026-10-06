package me.christianrobert.orapgsync.core.job.model.view;

import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents metadata for a database view including its columns and SQL definition.
 * This is used both for creating view stubs and for full view implementation.
 *
 * Phase 1 (Stubs): Uses column metadata to create views with empty result sets
 *                  (SELECT NULL::type AS column... WHERE false)
 * Phase 2 (Implementation): Uses sqlDefinition to create actual views with transformed SQL
 *
 * This is a pure data model without dependencies on other services.
 */
public class ViewMetadata {
    private String schema; // Database schema (user)
    private String viewName;
    private List<ColumnMetadata> columns;
    private String sqlDefinition; // Oracle view SQL definition (for Phase 2 implementation)

    public ViewMetadata(String schema, String viewName) {
        this.schema = schema;
        this.viewName = viewName;
        this.columns = new ArrayList<>();
    }

    // Getters
    public String getSchema() {
        return schema;
    }

    public String getViewName() {
        return viewName;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public String getSqlDefinition() {
        return sqlDefinition;
    }

    // Setters
    public void setSqlDefinition(String sqlDefinition) {
        this.sqlDefinition = sqlDefinition;
    }

    public void addColumn(ColumnMetadata column) {
        columns.add(column);
    }

    @Override
    public String toString() {
        return "ViewMetadata{schema='" + schema + "', viewName='" + viewName +
               "', columns=" + columns.size() +
               ", hasSql=" + (sqlDefinition != null && !sqlDefinition.isEmpty()) + "}";
    }
}
