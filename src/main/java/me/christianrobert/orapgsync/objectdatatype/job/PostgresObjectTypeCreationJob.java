package me.christianrobert.orapgsync.objectdatatype.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Dependent
public class PostgresObjectTypeCreationJob extends AbstractDatabaseWriteJob<ObjectTypeCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresObjectTypeCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "OBJECT_TYPE_CREATION";
    }

    @Override
    public Class<ObjectTypeCreationResult> getResultType() {
        return ObjectTypeCreationResult.class;
    }

    @Override
    protected void saveResultsToState(ObjectTypeCreationResult result) {
        stateService.setObjectTypeCreationResult(result);
    }

    @Override
    protected ObjectTypeCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL object type creation process");

        // Get Oracle object types from state
        List<ObjectDataTypeMetaData> oracleObjectTypes = getOracleObjectTypes();
        if (oracleObjectTypes.isEmpty()) {
            updateProgress(progressCallback, 100, "No object types to process",
                          "No Oracle object types found in state. Please extract Oracle object types first.");
            log.warn("No Oracle object types found in state for type creation");
            return new ObjectTypeCreationResult();
        }

        // Filter valid object types (exclude system schemas)
        List<ObjectDataTypeMetaData> validOracleObjectTypes = filterValidObjectTypes(oracleObjectTypes);

        updateProgress(progressCallback, 10, "Analyzing object types",
                      String.format("Found %d Oracle object types, %d are valid for creation",
                                   oracleObjectTypes.size(), validOracleObjectTypes.size()));

        ObjectTypeCreationResult result = new ObjectTypeCreationResult();

        if (validOracleObjectTypes.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid object types", "No valid Oracle object types to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected", "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL object types
            Set<String> existingPostgresTypes = getExistingPostgresObjectTypes(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing types",
                          String.format("Found %d existing PostgreSQL object types", existingPostgresTypes.size()));

            // Sort types by dependencies to avoid creation order issues
            List<ObjectDataTypeMetaData> sortedTypes = sortByDependencies(validOracleObjectTypes);

            // Determine which types need to be created
            List<ObjectDataTypeMetaData> typesToCreate = new ArrayList<>();
            List<ObjectDataTypeMetaData> typesAlreadyExisting = new ArrayList<>();

            for (ObjectDataTypeMetaData objectType : sortedTypes) {
                String qualifiedTypeName = getQualifiedTypeName(objectType);
                if (existingPostgresTypes.contains(qualifiedTypeName.toLowerCase())) {
                    typesAlreadyExisting.add(objectType);
                } else {
                    typesToCreate.add(objectType);
                }
            }

            // Mark already existing types as skipped
            for (ObjectDataTypeMetaData objectType : typesAlreadyExisting) {
                String qualifiedTypeName = getQualifiedTypeName(objectType);
                result.addSkippedType(qualifiedTypeName);
                log.info("Object type '{}' already exists in PostgreSQL, skipping", qualifiedTypeName);
            }

            updateProgress(progressCallback, 40, "Planning creation",
                          String.format("%d types to create, %d already exist",
                                       typesToCreate.size(), typesAlreadyExisting.size()));

            if (typesToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All types exist",
                              "All Oracle object types already exist in PostgreSQL");
                return result;
            }

            // Create missing object types
            int totalTypes = typesToCreate.size();
            int processedTypes = 0;

            for (ObjectDataTypeMetaData objectType : typesToCreate) {
                int progressPercentage = 40 + (processedTypes * 50 / totalTypes);
                String qualifiedTypeName = getQualifiedTypeName(objectType);
                updateProgress(progressCallback, progressPercentage,
                              String.format("Creating type: %s", qualifiedTypeName),
                              String.format("Type %d of %d", processedTypes + 1, totalTypes));

                try {
                    createObjectType(postgresConnection, objectType);
                    result.addCreatedType(qualifiedTypeName);
                    log.info("Successfully created PostgreSQL object type: {}", qualifiedTypeName);
                } catch (SQLException e) {
                    String errorMessage = String.format("Failed to create object type '%s': %s", qualifiedTypeName, e.getMessage());
                    String sqlStatement = generateCreateTypeSQL(objectType);
                    result.addError(qualifiedTypeName, errorMessage, sqlStatement);
                    log.error("Failed to create object type: {}", qualifiedTypeName, e);
                }

                processedTypes++;
            }

            updateProgress(progressCallback, 90, "Creation complete",
                          String.format("Created %d types, skipped %d existing, %d errors",
                                       result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Object type creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<ObjectDataTypeMetaData> getOracleObjectTypes() {
        return stateService.getOracleObjectDataTypeMetaData();
    }

    private List<ObjectDataTypeMetaData> filterValidObjectTypes(List<ObjectDataTypeMetaData> objectTypes) {
        List<ObjectDataTypeMetaData> validTypes = new ArrayList<>();
        for (ObjectDataTypeMetaData objectType : objectTypes) {
            if (!filterValidSchemas(List.of(objectType.getSchema())).isEmpty()) {
                validTypes.add(objectType);
            }
        }
        return validTypes;
    }

    private Set<String> getExistingPostgresObjectTypes(Connection connection) throws SQLException {
        Set<String> types = new HashSet<>();

        String sql = """
            SELECT n.nspname as schema_name, t.typname as type_name
            FROM pg_type t
            JOIN pg_namespace n ON t.typnamespace = n.oid
            WHERE t.typtype = 'c'
            AND n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String typeName = rs.getString("type_name");
                String qualifiedName = String.format("%s.%s", schemaName, typeName).toLowerCase();
                types.add(qualifiedName);
            }
        }

        log.debug("Found {} existing PostgreSQL object types", types.size());
        return types;
    }

    private List<ObjectDataTypeMetaData> sortByDependencies(List<ObjectDataTypeMetaData> objectTypes) {
        // For now, use simple alphabetical sorting
        // TODO: Implement proper dependency analysis based on variable types
        return objectTypes.stream()
                .sorted(Comparator.comparing(ObjectDataTypeMetaData::getName))
                .collect(Collectors.toList());
    }

    private String getQualifiedTypeName(ObjectDataTypeMetaData objectType) {
        return String.format("%s.%s", objectType.getSchema().toLowerCase(), objectType.getName().toLowerCase());
    }

    private void createObjectType(Connection connection, ObjectDataTypeMetaData objectType) throws SQLException {
        String sql = generateCreateTypeSQL(objectType);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private String generateCreateTypeSQL(ObjectDataTypeMetaData objectType) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TYPE ");
        sql.append(String.format("%s.%s AS (", objectType.getSchema().toLowerCase(), objectType.getName().toLowerCase()));

        List<String> fields = new ArrayList<>();
        for (ObjectDataTypeVariable variable : objectType.getVariables()) {
            String fieldName = variable.getName().toLowerCase();
            String fieldType = TypeConverter.toPostgre(variable.getDataType());
            if (fieldType == null) {
                fieldType = "text"; // Fallback for unknown types
                log.warn("Unknown data type '{}' for variable '{}', using 'text' as fallback",
                        variable.getDataType(), variable.getName());
            }
            fields.add(String.format("%s %s", fieldName, fieldType));
        }

        sql.append(String.join(", ", fields));
        sql.append(")");

        return sql.toString();
    }

    @Override
    protected String generateSummaryMessage(ObjectTypeCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Object type creation completed: %d created, %d skipped, %d errors",
                                    result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s", String.join(", ", result.getCreatedTypes())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s", String.join(", ", result.getSkippedTypes())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}