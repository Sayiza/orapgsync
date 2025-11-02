package me.christianrobert.orapgsync.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.function.FunctionImplementationResult;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import me.christianrobert.orapgsync.core.job.model.schema.SchemaCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.FKIndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceCreationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewStubCreationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionStubCreationResult;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodStubCreationResult;
import me.christianrobert.orapgsync.oraclecompat.model.OracleCompatInstallationResult;
import me.christianrobert.orapgsync.oraclecompat.model.OracleCompatVerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Synonyms: Map<owner/schema, Map<synonym_name, SynonymMetadata>>
    // Enables fast lookup: synonymsByOwnerAndName.get(schema).get(synonymName)
    // Supports Oracle resolution: current schema → PUBLIC fallback
    Map<String, Map<String, SynonymMetadata>> oracleSynonymsByOwnerAndName = new HashMap<>();

    List<ObjectDataTypeMetaData> oracleObjectDataTypeMetaData = new ArrayList<>();
    List<ObjectDataTypeMetaData> postgresObjectDataTypeMetaData = new ArrayList<>();
    ObjectTypeCreationResult objectTypeCreationResult;

    List<RowCountMetadata> oracleRowCountMetadata = new ArrayList<>();
    List<RowCountMetadata> postgresRowCountMetadata = new ArrayList<>();

    List<TableMetadata> oracleTableMetadata = new ArrayList<>();
    List<TableMetadata> postgresTableMetadata = new ArrayList<>();
    TableCreationResult tableCreationResult;

    List<SequenceMetadata> oracleSequenceMetadata = new ArrayList<>();
    List<SequenceMetadata> postgresSequenceMetadata = new ArrayList<>();
    SequenceCreationResult sequenceCreationResult;

    List<ViewMetadata> oracleViewMetadata = new ArrayList<>();
    List<ViewMetadata> postgresViewMetadata = new ArrayList<>();
    ViewStubCreationResult viewStubCreationResult;
    ViewImplementationResult viewImplementationResult;

    List<FunctionMetadata> oracleFunctionMetadata = new ArrayList<>();
    List<FunctionMetadata> postgresFunctionMetadata = new ArrayList<>();
    FunctionStubCreationResult functionStubCreationResult;
    FunctionImplementationResult functionImplementationResult;

    List<TypeMethodMetadata> oracleTypeMethodMetadata = new ArrayList<>();
    List<TypeMethodMetadata> postgresTypeMethodMetadata = new ArrayList<>();
    TypeMethodStubCreationResult typeMethodStubCreationResult;

    // Oracle Compatibility Layer
    OracleCompatInstallationResult oracleCompatInstallationResult;
    OracleCompatVerificationResult oracleCompatVerificationResult;

    ConstraintCreationResult constraintCreationResult;

    FKIndexCreationResult fkIndexCreationResult;

    DataTransferResult dataTransferResult;

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

    public Map<String, Map<String, SynonymMetadata>> getOracleSynonymsByOwnerAndName() {
        return oracleSynonymsByOwnerAndName;
    }

    public void setOracleSynonyms(List<SynonymMetadata> synonyms) {
        this.oracleSynonymsByOwnerAndName = new HashMap<>();
        for (SynonymMetadata synonym : synonyms) {
            this.oracleSynonymsByOwnerAndName
                .computeIfAbsent(synonym.getOwner(), k -> new HashMap<>())
                .put(synonym.getSynonymName(), synonym);
        }
        log.info("Updated Oracle synonyms state: {} synonyms across {} schemas",
                synonyms.size(), oracleSynonymsByOwnerAndName.size());
    }

    /**
     * Resolves a type reference that might be a synonym.
     * Follows Oracle resolution rules: current schema → PUBLIC.
     *
     * @param currentSchema The schema context for resolution
     * @param typeName The type name to resolve (may be a synonym)
     * @return The resolved target (schema.type) or null if not found
     */
    public String resolveSynonym(String currentSchema, String typeName) {
        if (currentSchema == null || typeName == null) {
            return null;
        }

        String normalizedSchema = currentSchema.toLowerCase();
        String normalizedType = typeName.toLowerCase();

        // Check current schema first
        Map<String, SynonymMetadata> currentSchemaSynonyms = oracleSynonymsByOwnerAndName.get(normalizedSchema);
        if (currentSchemaSynonyms != null && currentSchemaSynonyms.containsKey(normalizedType)) {
            SynonymMetadata synonym = currentSchemaSynonyms.get(normalizedType);
            log.debug("Resolved synonym {} in schema {} to {}", normalizedType, normalizedSchema, synonym.getQualifiedTarget());
            return synonym.getTableOwner() + "." + synonym.getTableName();
        }

        // Check PUBLIC schema as fallback
        Map<String, SynonymMetadata> publicSynonyms = oracleSynonymsByOwnerAndName.get("public");
        if (publicSynonyms != null && publicSynonyms.containsKey(normalizedType)) {
            SynonymMetadata synonym = publicSynonyms.get(normalizedType);
            log.debug("Resolved synonym {} via PUBLIC to {}", normalizedType, synonym.getQualifiedTarget());
            return synonym.getTableOwner() + "." + synonym.getTableName();
        }

        return null; // Not a synonym
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

    public DataTransferResult getDataTransferResult() {
        return dataTransferResult;
    }

    public void setDataTransferResult(DataTransferResult dataTransferResult) {
        this.dataTransferResult = dataTransferResult;
    }

    public List<SequenceMetadata> getOracleSequenceMetadata() {
        return oracleSequenceMetadata;
    }

    public void setOracleSequenceMetadata(List<SequenceMetadata> oracleSequenceMetadata) {
        this.oracleSequenceMetadata = oracleSequenceMetadata;
    }

    public List<SequenceMetadata> getPostgresSequenceMetadata() {
        return postgresSequenceMetadata;
    }

    public void setPostgresSequenceMetadata(List<SequenceMetadata> postgresSequenceMetadata) {
        this.postgresSequenceMetadata = postgresSequenceMetadata;
    }

    public SequenceCreationResult getSequenceCreationResult() {
        return sequenceCreationResult;
    }

    public void setSequenceCreationResult(SequenceCreationResult sequenceCreationResult) {
        this.sequenceCreationResult = sequenceCreationResult;
    }

    public List<ViewMetadata> getOracleViewMetadata() {
        return oracleViewMetadata;
    }

    public void setOracleViewMetadata(List<ViewMetadata> oracleViewMetadata) {
        this.oracleViewMetadata = oracleViewMetadata;
    }

    public List<ViewMetadata> getPostgresViewMetadata() {
        return postgresViewMetadata;
    }

    public void setPostgresViewMetadata(List<ViewMetadata> postgresViewMetadata) {
        this.postgresViewMetadata = postgresViewMetadata;
    }

    public ViewStubCreationResult getViewStubCreationResult() {
        return viewStubCreationResult;
    }

    public void setViewStubCreationResult(ViewStubCreationResult viewStubCreationResult) {
        this.viewStubCreationResult = viewStubCreationResult;
    }

    public ViewImplementationResult getViewImplementationResult() {
        return viewImplementationResult;
    }

    public void setViewImplementationResult(ViewImplementationResult viewImplementationResult) {
        this.viewImplementationResult = viewImplementationResult;
    }

    public List<FunctionMetadata> getOracleFunctionMetadata() {
        return oracleFunctionMetadata;
    }

    public void setOracleFunctionMetadata(List<FunctionMetadata> oracleFunctionMetadata) {
        this.oracleFunctionMetadata = oracleFunctionMetadata;
    }

    public List<FunctionMetadata> getPostgresFunctionMetadata() {
        return postgresFunctionMetadata;
    }

    public void setPostgresFunctionMetadata(List<FunctionMetadata> postgresFunctionMetadata) {
        this.postgresFunctionMetadata = postgresFunctionMetadata;
    }

    public FunctionStubCreationResult getFunctionStubCreationResult() {
        return functionStubCreationResult;
    }

    public void setFunctionStubCreationResult(FunctionStubCreationResult functionStubCreationResult) {
        this.functionStubCreationResult = functionStubCreationResult;
    }

    public FunctionImplementationResult getFunctionImplementationResult() {
        return functionImplementationResult;
    }

    public void setFunctionImplementationResult(FunctionImplementationResult functionImplementationResult) {
        this.functionImplementationResult = functionImplementationResult;
    }

    public List<TypeMethodMetadata> getOracleTypeMethodMetadata() {
        return oracleTypeMethodMetadata;
    }

    public void setOracleTypeMethodMetadata(List<TypeMethodMetadata> oracleTypeMethodMetadata) {
        this.oracleTypeMethodMetadata = oracleTypeMethodMetadata;
    }

    public List<TypeMethodMetadata> getPostgresTypeMethodMetadata() {
        return postgresTypeMethodMetadata;
    }

    public void setPostgresTypeMethodMetadata(List<TypeMethodMetadata> postgresTypeMethodMetadata) {
        this.postgresTypeMethodMetadata = postgresTypeMethodMetadata;
    }

    public TypeMethodStubCreationResult getTypeMethodStubCreationResult() {
        return typeMethodStubCreationResult;
    }

    public void setTypeMethodStubCreationResult(TypeMethodStubCreationResult typeMethodStubCreationResult) {
        this.typeMethodStubCreationResult = typeMethodStubCreationResult;
    }

    public OracleCompatInstallationResult getOracleCompatInstallationResult() {
        return oracleCompatInstallationResult;
    }

    public void setOracleCompatInstallationResult(OracleCompatInstallationResult oracleCompatInstallationResult) {
        this.oracleCompatInstallationResult = oracleCompatInstallationResult;
    }

    public OracleCompatVerificationResult getOracleCompatVerificationResult() {
        return oracleCompatVerificationResult;
    }

    public void setOracleCompatVerificationResult(OracleCompatVerificationResult oracleCompatVerificationResult) {
        this.oracleCompatVerificationResult = oracleCompatVerificationResult;
    }

    public ConstraintCreationResult getConstraintCreationResult() {
        return constraintCreationResult;
    }

    public void setConstraintCreationResult(ConstraintCreationResult constraintCreationResult) {
        this.constraintCreationResult = constraintCreationResult;
    }

    public FKIndexCreationResult getFkIndexCreationResult() {
        return fkIndexCreationResult;
    }

    public void setFkIndexCreationResult(FKIndexCreationResult fkIndexCreationResult) {
        this.fkIndexCreationResult = fkIndexCreationResult;
    }

    public void resetState() {
        log.info("Resetting all state to default values");
        this.oracleSchemaNames = new ArrayList<>();
        this.postgresSchemaNames = new ArrayList<>();
        this.schemaCreationResult = null;
        this.oracleSynonymsByOwnerAndName = new HashMap<>();
        this.oracleObjectDataTypeMetaData = new ArrayList<>();
        this.postgresObjectDataTypeMetaData = new ArrayList<>();
        this.objectTypeCreationResult = null;
        this.oracleRowCountMetadata = new ArrayList<>();
        this.postgresRowCountMetadata = new ArrayList<>();
        this.oracleTableMetadata = new ArrayList<>();
        this.postgresTableMetadata = new ArrayList<>();
        this.tableCreationResult = null;
        this.oracleSequenceMetadata = new ArrayList<>();
        this.postgresSequenceMetadata = new ArrayList<>();
        this.sequenceCreationResult = null;
        this.oracleViewMetadata = new ArrayList<>();
        this.postgresViewMetadata = new ArrayList<>();
        this.viewStubCreationResult = null;
        this.viewImplementationResult = null;
        this.oracleFunctionMetadata = new ArrayList<>();
        this.postgresFunctionMetadata = new ArrayList<>();
        this.functionStubCreationResult = null;
        this.functionImplementationResult = null;
        this.oracleTypeMethodMetadata = new ArrayList<>();
        this.postgresTypeMethodMetadata = new ArrayList<>();
        this.typeMethodStubCreationResult = null;
        this.oracleCompatInstallationResult = null;
        this.oracleCompatVerificationResult = null;
        this.constraintCreationResult = null;
        this.fkIndexCreationResult = null;
        this.dataTransferResult = null;
    }
}