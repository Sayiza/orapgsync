package me.christianrobert.orapgsync.core.event;

import java.util.List;

/**
 * Event fired when the list of schemas is updated for a database.
 */
public class SchemaListUpdatedEvent extends DatabaseMetadataUpdatedEvent<String> {

    public SchemaListUpdatedEvent(String sourceDatabase, List<String> schemas) {
        super(sourceDatabase, "SCHEMA_LIST", schemas);
    }

    /**
     * Factory method for Oracle schema list updates.
     */
    public static SchemaListUpdatedEvent forOracle(List<String> schemas) {
        return new SchemaListUpdatedEvent("ORACLE", schemas);
    }

    /**
     * Factory method for PostgreSQL schema list updates.
     */
    public static SchemaListUpdatedEvent forPostgres(List<String> schemas) {
        return new SchemaListUpdatedEvent("POSTGRES", schemas);
    }
}