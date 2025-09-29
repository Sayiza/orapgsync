package me.christianrobert.orapgsync.rowcount.model;

/**
 * Represents row count metadata for a specific table.
 * This is a pure data model without dependencies on other services.
 */
public class RowCountMetadata {
    private String schema;
    private String tableName;
    private long rowCount;
    private long extractionTimestamp;

    public RowCountMetadata(String schema, String tableName, long rowCount, long extractionTimestamp) {
        this.schema = schema;
        this.tableName = tableName;
        this.rowCount = rowCount;
        this.extractionTimestamp = extractionTimestamp;
    }

    public String getSchema() {
        return schema;
    }

    public String getTableName() {
        return tableName;
    }

    public long getRowCount() {
        return rowCount;
    }

    public long getExtractionTimestamp() {
        return extractionTimestamp;
    }

    @Override
    public String toString() {
        return "RowCountMetadata{" +
                "schema='" + schema + '\'' +
                ", tableName='" + tableName + '\'' +
                ", rowCount=" + rowCount +
                ", extractionTimestamp=" + extractionTimestamp +
                '}';
    }
}