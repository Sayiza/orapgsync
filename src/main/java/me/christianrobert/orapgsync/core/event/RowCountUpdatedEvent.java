package me.christianrobert.orapgsync.core.event;

import me.christianrobert.orapgsync.rowcount.model.RowCountMetadata;

import java.util.List;

/**
 * Event fired when row count metadata is updated for a database.
 */
public class RowCountUpdatedEvent extends DatabaseMetadataUpdatedEvent<RowCountMetadata> {

    public RowCountUpdatedEvent(String sourceDatabase, List<RowCountMetadata> rowCountMetadata) {
        super(sourceDatabase, "ROW_COUNT", rowCountMetadata);
    }

    /**
     * Factory method for Oracle row count updates.
     */
    public static RowCountUpdatedEvent forOracle(List<RowCountMetadata> rowCountMetadata) {
        return new RowCountUpdatedEvent("ORACLE", rowCountMetadata);
    }

    /**
     * Factory method for PostgreSQL row count updates.
     */
    public static RowCountUpdatedEvent forPostgres(List<RowCountMetadata> rowCountMetadata) {
        return new RowCountUpdatedEvent("POSTGRES", rowCountMetadata);
    }
}