package me.christianrobert.orapgsync.core.event;

import me.christianrobert.orapgsync.table.model.TableMetadata;

import java.util.List;

/**
 * Event fired when table metadata is updated for a database.
 */
public class TableMetadataUpdatedEvent extends DatabaseMetadataUpdatedEvent<TableMetadata> {

    public TableMetadataUpdatedEvent(String sourceDatabase, List<TableMetadata> tableMetadata) {
        super(sourceDatabase, "TABLE_METADATA", tableMetadata);
    }

    /**
     * Factory method for Oracle table metadata updates.
     */
    public static TableMetadataUpdatedEvent forOracle(List<TableMetadata> tableMetadata) {
        return new TableMetadataUpdatedEvent("ORACLE", tableMetadata);
    }

    /**
     * Factory method for PostgreSQL table metadata updates.
     */
    public static TableMetadataUpdatedEvent forPostgres(List<TableMetadata> tableMetadata) {
        return new TableMetadataUpdatedEvent("POSTGRES", tableMetadata);
    }
}