package me.christianrobert.orapgsync.core.event;

import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;

import java.util.List;

/**
 * Event fired when object data type metadata is updated for a database.
 */
public class ObjectDataTypeUpdatedEvent extends DatabaseMetadataUpdatedEvent<ObjectDataTypeMetaData> {

    public ObjectDataTypeUpdatedEvent(String sourceDatabase, List<ObjectDataTypeMetaData> objectDataTypes) {
        super(sourceDatabase, "OBJECT_DATATYPE", objectDataTypes);
    }

    /**
     * Factory method for Oracle object data type updates.
     */
    public static ObjectDataTypeUpdatedEvent forOracle(List<ObjectDataTypeMetaData> objectDataTypes) {
        return new ObjectDataTypeUpdatedEvent("ORACLE", objectDataTypes);
    }

    /**
     * Factory method for PostgreSQL object data type updates.
     */
    public static ObjectDataTypeUpdatedEvent forPostgres(List<ObjectDataTypeMetaData> objectDataTypes) {
        return new ObjectDataTypeUpdatedEvent("POSTGRES", objectDataTypes);
    }
}