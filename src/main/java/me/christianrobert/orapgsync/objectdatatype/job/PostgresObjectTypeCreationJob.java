package me.christianrobert.orapgsync.objectdatatype.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectTypeCreationResult;
import me.christianrobert.orapgsync.objectdatatype.service.TypeDependencyAnalyzer;
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

        updateProgress(progressCallback, 10, "Normalizing object types",
                      String.format("Found %d Oracle object types, %d are valid for creation",
                                   oracleObjectTypes.size(), validOracleObjectTypes.size()));

        // Normalize object types by resolving all synonym references
        // This ensures dependency analysis and type creation work with actual type references
        List<ObjectDataTypeMetaData> normalizedObjectTypes = normalizeObjectTypes(validOracleObjectTypes);

        updateProgress(progressCallback, 15, "Analyzing object types",
                      String.format("Normalized %d object types for dependency analysis", normalizedObjectTypes.size()));

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
            // Use normalized types so dependency analysis sees the true dependencies (not synonyms)
            List<ObjectDataTypeMetaData> sortedTypes = sortByDependencies(normalizedObjectTypes);

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
                log.debug("Object type '{}' already exists in PostgreSQL, skipping", qualifiedTypeName);
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
                    log.debug("Successfully created PostgreSQL object type: {}", qualifiedTypeName);
                } catch (SQLException e) {
                    String sqlStatement = generateCreateTypeSQL(objectType);
                    String errorMessage = String.format("Failed to create object type '%s': %s", qualifiedTypeName, e.getMessage());
                    result.addError(qualifiedTypeName, errorMessage, sqlStatement);
                    log.error("Failed to create object type '{}': {}", qualifiedTypeName, e.getMessage());
                    log.error("Failed SQL statement: {}", sqlStatement);
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

    /**
     * Normalizes object types by resolving all synonym references in type variables.
     * This preprocessing step ensures that:
     * 1. Dependency analysis sees the true type dependencies (not synonyms)
     * 2. Type creation SQL uses the correct target types
     * 3. Synonym resolution happens only once per type reference
     *
     * @param objectTypes The original object types with potential synonym references
     * @return Normalized copies with all synonyms resolved to their targets
     */
    private List<ObjectDataTypeMetaData> normalizeObjectTypes(List<ObjectDataTypeMetaData> objectTypes) {
        log.info("Normalizing {} object types by resolving synonym references", objectTypes.size());

        List<ObjectDataTypeMetaData> normalizedTypes = new ArrayList<>();
        int synonymsResolved = 0;

        for (ObjectDataTypeMetaData originalType : objectTypes) {
            try {
                // Create a deep copy with normalized variable references
                List<ObjectDataTypeVariable> normalizedVariables = new ArrayList<>();

                for (ObjectDataTypeVariable originalVariable : originalType.getVariables()) {
                    ObjectDataTypeVariable normalizedVariable;

                    // Only resolve synonyms for custom (user-defined) types
                    if (originalVariable.isCustomDataType()) {
                        String owner = originalVariable.getDataTypeOwner();
                        String typeName = originalVariable.getDataType();

                        // Extract the base type name without size/precision info
                        String baseTypeName = extractBaseTypeName(typeName);

                        // Try to resolve as a synonym
                        String resolvedTarget = stateService.resolveSynonym(owner, baseTypeName);

                        if (resolvedTarget != null) {
                            // Parse the resolved target (format: "schema.typename")
                            String[] parts = resolvedTarget.split("\\.");
                            if (parts.length == 2) {
                                String resolvedOwner = parts[0];
                                String resolvedTypeName = parts[1];

                                // Preserve any size/precision info from the original type
                                String typeWithSize = typeName.substring(baseTypeName.length());
                                String fullResolvedType = resolvedTypeName + typeWithSize;

                                normalizedVariable = new ObjectDataTypeVariable(
                                    originalVariable.getName(),
                                    fullResolvedType,
                                    resolvedOwner
                                );

                                synonymsResolved++;
                                log.debug("Resolved synonym {}.{} -> {}.{} for variable '{}' in type {}.{}",
                                    owner, baseTypeName, resolvedOwner, resolvedTypeName,
                                    originalVariable.getName(), originalType.getSchema(), originalType.getName());
                            } else {
                                // Malformed resolution result, use original
                                normalizedVariable = new ObjectDataTypeVariable(
                                    originalVariable.getName(),
                                    typeName,
                                    owner
                                );
                                log.warn("Malformed synonym resolution result: {}", resolvedTarget);
                            }
                        } else {
                            // Not a synonym, use original variable
                            normalizedVariable = new ObjectDataTypeVariable(
                                originalVariable.getName(),
                                typeName,
                                owner
                            );
                        }
                    } else {
                        // Built-in type, use original variable
                        normalizedVariable = new ObjectDataTypeVariable(
                            originalVariable.getName(),
                            originalVariable.getDataType(),
                            originalVariable.getDataTypeOwner()
                        );
                    }

                    normalizedVariables.add(normalizedVariable);
                }

                // Create a copy of the ObjectDataTypeMetaData with normalized variables
                ObjectDataTypeMetaData normalizedType = createObjectDataTypeMetaDataCopy(
                    originalType.getSchema(),
                    originalType.getName(),
                    normalizedVariables
                );

                normalizedTypes.add(normalizedType);

            } catch (Exception e) {
                log.error("Failed to normalize object type {}.{}, using original",
                    originalType.getSchema(), originalType.getName(), e);
                normalizedTypes.add(originalType); // Fall back to original on error
            }
        }

        log.info("Normalization complete: {} object types processed, {} synonym references resolved",
            normalizedTypes.size(), synonymsResolved);

        return normalizedTypes;
    }

    /**
     * Extracts the base type name without size/precision information.
     * For example: "VARCHAR2(100)" -> "VARCHAR2", "NUMBER(10,2)" -> "NUMBER"
     */
    private String extractBaseTypeName(String dataType) {
        int parenIndex = dataType.indexOf('(');
        if (parenIndex > 0) {
            return dataType.substring(0, parenIndex);
        }
        return dataType;
    }

    /**
     * Creates a copy of ObjectDataTypeMetaData with the given schema, name, and variables.
     */
    private ObjectDataTypeMetaData createObjectDataTypeMetaDataCopy(String schema, String name,
                                                                     List<ObjectDataTypeVariable> variables) {
        return new ObjectDataTypeMetaData(schema, name, new ArrayList<>(variables));
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
        log.info("Analyzing dependencies for {} object types", objectTypes.size());

        // Use TypeDependencyAnalyzer to perform topological sort
        TypeDependencyAnalyzer.DependencyAnalysisResult analysisResult =
                TypeDependencyAnalyzer.analyzeDependencies(objectTypes);

        // Log any circular dependencies found
        if (analysisResult.hasCircularDependencies()) {
            log.warn("Found {} circular dependencies in object types:",
                    analysisResult.getCircularDependencies().size());
            for (TypeDependencyAnalyzer.CircularDependency cycle : analysisResult.getCircularDependencies()) {
                log.warn("  Circular dependency cycle: {}", cycle);
            }
            log.warn("Types in circular dependencies will be created last and may fail. " +
                    "Manual intervention may be required.");
        }

        List<ObjectDataTypeMetaData> sortedTypes = analysisResult.getSortedTypes();
        log.info("Dependency analysis complete. Types sorted in creation order.");

        return sortedTypes;
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
            String fieldType;

            // Check if this is a custom (user-defined) type
            if (variable.isCustomDataType()) {
                String oracleType = variable.getDataType().toLowerCase();
                String owner = variable.getDataTypeOwner().toLowerCase();

                // Check if it's XMLTYPE - has direct PostgreSQL xml type mapping
                if (OracleTypeClassifier.isXmlType(owner, oracleType)) {
                    fieldType = "xml";
                    log.debug("Oracle XMLTYPE for variable '{}' in type {}.{} mapped to PostgreSQL xml type",
                            variable.getName(), objectType.getSchema(), objectType.getName());
                }
                // Check if it's a complex Oracle system type that needs jsonb serialization
                else if (OracleTypeClassifier.isComplexOracleSystemType(owner, oracleType)) {
                    fieldType = "jsonb";
                    log.debug("Complex Oracle system type '{}.{}' for variable '{}' in type {}.{} will use jsonb",
                            owner, oracleType, variable.getName(), objectType.getSchema(), objectType.getName());
                } else {
                    // User-defined type - use the fully qualified type name (already normalized, synonyms resolved)
                    fieldType = variable.getQualifiedTypeName();
                    log.debug("Using custom type '{}' for variable '{}' in type {}.{}",
                            fieldType, variable.getName(), objectType.getSchema(), objectType.getName());
                }
            } else {
                // Convert built-in Oracle types to PostgreSQL types
                fieldType = TypeConverter.toPostgre(variable.getDataType());
                if (fieldType == null) {
                    fieldType = "text"; // Fallback for unknown types
                    log.warn("Unknown data type '{}' for variable '{}', using 'text' as fallback",
                            variable.getDataType(), variable.getName());
                }
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