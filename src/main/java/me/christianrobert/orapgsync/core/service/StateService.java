package me.christianrobert.orapgsync.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.event.ObjectDataTypeUpdatedEvent;
import me.christianrobert.orapgsync.core.event.SchemaListUpdatedEvent;
import me.christianrobert.orapgsync.core.event.TableMetadataUpdatedEvent;
import me.christianrobert.orapgsync.core.state.ObjectDataTypeStateManager;
import me.christianrobert.orapgsync.core.state.SchemaStateManager;
import me.christianrobert.orapgsync.core.state.TableMetadataStateManager;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Central state coordination service that acts as an event dispatcher and query coordinator.
 * This service fires CDI events for state updates and delegates queries to specific state managers.
 *
 * Note: This service is being kept for backward compatibility during the refactoring process.
 * New code should use the specific state managers directly or listen to events.
 */
@ApplicationScoped
public class StateService {

    private static final Logger log = LoggerFactory.getLogger(StateService.class);

    @Inject
    private Event<TableMetadataUpdatedEvent> tableMetadataEvent;

    @Inject
    private Event<ObjectDataTypeUpdatedEvent> objectDataTypeEvent;

    @Inject
    private Event<SchemaListUpdatedEvent> schemaListEvent;

    @Inject
    private TableMetadataStateManager tableMetadataStateManager;

    @Inject
    private ObjectDataTypeStateManager objectDataTypeStateManager;

    @Inject
    private SchemaStateManager schemaStateManager;

    // ==================== Query Methods (delegate to state managers) ====================

    public List<String> getOracleSchemaNames() {
        return schemaStateManager.getOracleSchemaNames();
    }

    public List<String> getPostgresSchemaNames() {
        return schemaStateManager.getPostgresSchemaNames();
    }

    public List<ObjectDataTypeMetaData> getPostgresObjectDataTypeMetaData() {
        return objectDataTypeStateManager.getPostgresObjectDataTypeMetaData();
    }

    public List<ObjectDataTypeMetaData> getOracleObjectDataTypeMetaData() {
        return objectDataTypeStateManager.getOracleObjectDataTypeMetaData();
    }

    public List<TableMetadata> getOracleTableMetadata() {
        return tableMetadataStateManager.getOracleTableMetadata();
    }

    public List<TableMetadata> getPostgresTableMetadata() {
        return tableMetadataStateManager.getPostgresTableMetadata();
    }

    // ==================== Update Methods (fire events) ====================

    public void updateOracleSchemaNames(List<String> schemas) {
        log.info("Firing Oracle schema list update event with {} schemas", schemas.size());
        schemaListEvent.fire(SchemaListUpdatedEvent.forOracle(schemas));
    }

    public void updatePostgresSchemaNames(List<String> schemas) {
        log.info("Firing PostgreSQL schema list update event with {} schemas", schemas.size());
        schemaListEvent.fire(SchemaListUpdatedEvent.forPostgres(schemas));
    }

    public void updateOracleObjectDataTypeMetaData(List<ObjectDataTypeMetaData> objectDataTypes) {
        log.info("Firing Oracle object data type update event with {} types", objectDataTypes.size());
        objectDataTypeEvent.fire(ObjectDataTypeUpdatedEvent.forOracle(objectDataTypes));
    }

    public void updatePostgresObjectDataTypeMetaData(List<ObjectDataTypeMetaData> objectDataTypes) {
        log.info("Firing PostgreSQL object data type update event with {} types", objectDataTypes.size());
        objectDataTypeEvent.fire(ObjectDataTypeUpdatedEvent.forPostgres(objectDataTypes));
    }

    public void updateOracleTableMetadata(List<TableMetadata> tableMetadata) {
        log.info("Firing Oracle table metadata update event with {} tables", tableMetadata.size());
        tableMetadataEvent.fire(TableMetadataUpdatedEvent.forOracle(tableMetadata));
    }

    public void updatePostgresTableMetadata(List<TableMetadata> tableMetadata) {
        log.info("Firing PostgreSQL table metadata update event with {} tables", tableMetadata.size());
        tableMetadataEvent.fire(TableMetadataUpdatedEvent.forPostgres(tableMetadata));
    }

}