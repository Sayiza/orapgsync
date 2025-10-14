package me.christianrobert.orapgsync.core.job.model.view;

import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents metadata for a database view including its columns.
 * This is used to create view stubs - views with correct column structure but empty result sets.
 *
 * Note: This metadata does NOT include the actual view SQL, as view stubs are created with
 * SELECT NULL::type AS column... WHERE false to maintain structure without implementation.
 *
 * This is a pure data model without dependencies on other services.
 */
public class ViewMetadata {
    private String schema; // Database schema (user)
    private String viewName;
    private List<ColumnMetadata> columns;

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

    public void addColumn(ColumnMetadata column) {
        columns.add(column);
    }

    @Override
    public String toString() {
        return "ViewMetadata{schema='" + schema + "', viewName='" + viewName +
               "', columns=" + columns.size() + "}";
    }
}
