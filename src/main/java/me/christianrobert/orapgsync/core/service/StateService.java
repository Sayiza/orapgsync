package me.christianrobert.orapgsync.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import me.christianrobert.orapgsync.core.job.model.schema.SchemaCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    List<String> oracleSchemaNames = new ArrayList<>();
    List<String> postgresSchemaNames = new ArrayList<>();
    SchemaCreationResult schemaCreationResult;

    List<ObjectDataTypeMetaData> oracleObjectDataTypeMetaData = new ArrayList<>();
    List<ObjectDataTypeMetaData> postgresObjectDataTypeMetaData = new ArrayList<>();
    ObjectTypeCreationResult objectTypeCreationResult;

    List<RowCountMetadata> oracleRowCountMetadata = new ArrayList<>();
    List<RowCountMetadata> postgresRowCountMetadata = new ArrayList<>();

    List<TableMetadata> oracleTableMetadata = new ArrayList<>();
    List<TableMetadata> postgresTableMetadata = new ArrayList<>();
    TableCreationResult tableCreationResult;

    public List<String> getOracleSchemaNames() {
        return oracleSchemaNames;
    }

    public void setOracleSchemaNames(List<String> oracleSchemaNames) {
        this.oracleSchemaNames = oracleSchemaNames;
    }

    public List<String> getPostgresSchemaNames() {
        return postgresSchemaNames;
    }

    public void setPostgresSchemaNames(List<String> postgresSchemaNames) {
        this.postgresSchemaNames = postgresSchemaNames;
    }

    public SchemaCreationResult getSchemaCreationResult() {
        return schemaCreationResult;
    }

    public void setSchemaCreationResult(SchemaCreationResult schemaCreationResult) {
        this.schemaCreationResult = schemaCreationResult;
    }

    public List<ObjectDataTypeMetaData> getOracleObjectDataTypeMetaData() {
        return oracleObjectDataTypeMetaData;
    }

    public void setOracleObjectDataTypeMetaData(List<ObjectDataTypeMetaData> oracleObjectDataTypeMetaData) {
        this.oracleObjectDataTypeMetaData = oracleObjectDataTypeMetaData;
    }

    public List<ObjectDataTypeMetaData> getPostgresObjectDataTypeMetaData() {
        return postgresObjectDataTypeMetaData;
    }

    public void setPostgresObjectDataTypeMetaData(List<ObjectDataTypeMetaData> postgresObjectDataTypeMetaData) {
        this.postgresObjectDataTypeMetaData = postgresObjectDataTypeMetaData;
    }

    public ObjectTypeCreationResult getObjectTypeCreationResult() {
        return objectTypeCreationResult;
    }

    public void setObjectTypeCreationResult(ObjectTypeCreationResult objectTypeCreationResult) {
        this.objectTypeCreationResult = objectTypeCreationResult;
    }

    public List<RowCountMetadata> getOracleRowCountMetadata() {
        return oracleRowCountMetadata;
    }

    public void setOracleRowCountMetadata(List<RowCountMetadata> oracleRowCountMetadata) {
        this.oracleRowCountMetadata = oracleRowCountMetadata;
    }

    public List<RowCountMetadata> getPostgresRowCountMetadata() {
        return postgresRowCountMetadata;
    }

    public void setPostgresRowCountMetadata(List<RowCountMetadata> postgresRowCountMetadata) {
        this.postgresRowCountMetadata = postgresRowCountMetadata;
    }

    public List<TableMetadata> getOracleTableMetadata() {
        return oracleTableMetadata;
    }

    public void setOracleTableMetadata(List<TableMetadata> oracleTableMetadata) {
        this.oracleTableMetadata = oracleTableMetadata;
    }

    public List<TableMetadata> getPostgresTableMetadata() {
        return postgresTableMetadata;
    }

    public void setPostgresTableMetadata(List<TableMetadata> postgresTableMetadata) {
        this.postgresTableMetadata = postgresTableMetadata;
    }

    public TableCreationResult getTableCreationResult() {
        return tableCreationResult;
    }

    public void setTableCreationResult(TableCreationResult tableCreationResult) {
        this.tableCreationResult = tableCreationResult;
    }
}