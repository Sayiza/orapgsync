package me.christianrobert.orapgsync.typemethod.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodParameter;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodStubCreationResult;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Creates type method stubs (member/static functions and procedures) in PostgreSQL database.
 *
 * Type method stubs are created as standalone PostgreSQL functions with correct signatures but
 * empty implementations. Member methods include an implicit SELF parameter (the type instance).
 *
 * Naming convention: typename__methodname (double underscore)
 */
@Dependent
public class PostgresTypeMethodStubCreationJob extends AbstractDatabaseWriteJob<TypeMethodStubCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTypeMethodStubCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "TYPE_METHOD_STUB_CREATION";
    }

    @Override
    public Class<TypeMethodStubCreationResult> getResultType() {
        return TypeMethodStubCreationResult.class;
    }

    @Override
    protected void saveResultsToState(TypeMethodStubCreationResult result) {
        stateService.setTypeMethodStubCreationResult(result);
    }

    @Override
    protected TypeMethodStubCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL type method stub creation process");

        // Get Oracle type methods from state
        List<TypeMethodMetadata> oracleTypeMethods = getOracleTypeMethods();
        if (oracleTypeMethods.isEmpty()) {
            updateProgress(progressCallback, 100, "No type methods to process",
                    "No Oracle type methods found in state. Please extract Oracle type methods first.");
            log.warn("No Oracle type methods found in state for stub creation");
            return new TypeMethodStubCreationResult();
        }

        // Filter valid type methods (exclude system schemas)
        List<TypeMethodMetadata> validOracleTypeMethods = filterValidTypeMethods(oracleTypeMethods);

        updateProgress(progressCallback, 10, "Analyzing type methods",
                String.format("Found %d Oracle type methods, %d are valid for creation",
                        oracleTypeMethods.size(), validOracleTypeMethods.size()));

        TypeMethodStubCreationResult result = new TypeMethodStubCreationResult();

        if (validOracleTypeMethods.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid type methods",
                    "No valid Oracle type methods to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL functions/procedures
            Set<String> existingPostgresFunctions = getExistingPostgresFunctions(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing type methods",
                    String.format("Found %d existing PostgreSQL functions/procedures", existingPostgresFunctions.size()));

            // Determine which type methods need to be created
            List<TypeMethodMetadata> methodsToCreate = new ArrayList<>();
            List<TypeMethodMetadata> methodsAlreadyExisting = new ArrayList<>();

            for (TypeMethodMetadata method : validOracleTypeMethods) {
                String qualifiedMethodName = method.getQualifiedName();
                if (existingPostgresFunctions.contains(qualifiedMethodName.toLowerCase())) {
                    methodsAlreadyExisting.add(method);
                } else {
                    methodsToCreate.add(method);
                }
            }

            // Mark already existing type methods as skipped
            for (TypeMethodMetadata method : methodsAlreadyExisting) {
                String qualifiedMethodName = method.getQualifiedName();
                result.addSkippedMethod(qualifiedMethodName);
                log.debug("Type method '{}' already exists in PostgreSQL, skipping", qualifiedMethodName);
            }

            updateProgress(progressCallback, 40, "Planning creation",
                    String.format("%d type methods to create, %d already exist",
                            methodsToCreate.size(), methodsAlreadyExisting.size()));

            if (methodsToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All type methods exist",
                        "All Oracle type methods already exist in PostgreSQL");
                return result;
            }

            // Create type method stubs
            createTypeMethodStubs(postgresConnection, methodsToCreate, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d type method stubs, skipped %d existing, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Type method stub creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<TypeMethodMetadata> getOracleTypeMethods() {
        return stateService.getOracleTypeMethodMetadata();
    }

    private List<TypeMethodMetadata> filterValidTypeMethods(List<TypeMethodMetadata> methods) {
        List<TypeMethodMetadata> validMethods = new ArrayList<>();
        for (TypeMethodMetadata method : methods) {
            if (!filterValidSchemas(List.of(method.getSchema())).isEmpty()) {
                validMethods.add(method);
            }
        }
        return validMethods;
    }

    private Set<String> getExistingPostgresFunctions(Connection connection) throws SQLException {
        Set<String> functions = new HashSet<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                p.proname AS function_name
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String functionName = rs.getString("function_name");
                String qualifiedName = String.format("%s.%s", schemaName, functionName).toLowerCase();
                functions.add(qualifiedName);
            }
        }

        log.debug("Found {} existing PostgreSQL functions/procedures", functions.size());
        return functions;
    }

    private void createTypeMethodStubs(Connection connection, List<TypeMethodMetadata> methods,
                                        TypeMethodStubCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalMethods = methods.size();
        int processedMethods = 0;

        for (TypeMethodMetadata method : methods) {
            int progressPercentage = 40 + (processedMethods * 50 / totalMethods);
            String qualifiedMethodName = method.getQualifiedName();
            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating %s %s stub: %s",
                            method.getMemberTypeDescription(),
                            method.isFunction() ? "function" : "procedure",
                            qualifiedMethodName),
                    String.format("Method %d of %d", processedMethods + 1, totalMethods));

            try {
                createTypeMethodStub(connection, method);
                result.addCreatedMethod(qualifiedMethodName);
                log.debug("Successfully created PostgreSQL type method stub: {} ({})",
                        qualifiedMethodName, method.getMemberTypeDescription());
            } catch (SQLException e) {
                String sqlStatement = generateCreateTypeMethodStubSQL(method);
                String errorMessage = String.format("Failed to create type method stub '%s': %s",
                        qualifiedMethodName, e.getMessage());
                result.addError(qualifiedMethodName, errorMessage, sqlStatement);
                log.error("Failed to create type method stub '{}': {}", qualifiedMethodName, e.getMessage());
                log.error("Failed SQL statement: {}", sqlStatement);
            }

            processedMethods++;
        }
    }

    private void createTypeMethodStub(Connection connection, TypeMethodMetadata method) throws SQLException {
        String sql = generateCreateTypeMethodStubSQL(method);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    /**
     * Generates SQL for creating a type method stub.
     * Member methods include SELF parameter, static methods do not.
     * Functions return NULL, procedures have empty body.
     */
    private String generateCreateTypeMethodStubSQL(TypeMethodMetadata method) {
        StringBuilder sql = new StringBuilder();

        sql.append("CREATE OR REPLACE ");
        sql.append(method.isFunction() ? "FUNCTION " : "PROCEDURE ");
        sql.append(method.getSchema().toLowerCase());
        sql.append(".");
        sql.append(method.getPostgresName());
        sql.append("(");

        // Add parameters
        List<String> paramDefinitions = new ArrayList<>();

        // Member methods: Add implicit SELF parameter first
        if (method.isMemberMethod()) {
            String selfParam = String.format("self %s.%s",
                    method.getSchema().toLowerCase(),
                    method.getTypeName().toLowerCase());
            paramDefinitions.add(selfParam);
        }

        // Add regular parameters
        for (TypeMethodParameter param : method.getParameters()) {
            String paramDef = generateParameterDefinition(param, method);
            paramDefinitions.add(paramDef);
        }

        sql.append(String.join(", ", paramDefinitions));
        sql.append(")");

        // Add return type for functions
        if (method.isFunction()) {
            sql.append(" RETURNS ");
            sql.append(mapReturnType(method));
        }

        sql.append(" AS $$\n");
        sql.append("BEGIN\n");

        if (method.isFunction()) {
            sql.append("    RETURN NULL; -- Stub: Original Oracle type method ");
        } else {
            sql.append("    -- Stub: Original Oracle type method ");
        }

        // Add comment about original location
        sql.append(method.getSchema().toUpperCase());
        sql.append(".");
        sql.append(method.getTypeName().toUpperCase());
        sql.append(".");
        sql.append(method.getMethodName().toUpperCase());
        sql.append(" (").append(method.getMemberTypeDescription()).append(")");

        sql.append("\n");
        sql.append("END;\n");
        sql.append("$$ LANGUAGE plpgsql");

        return sql.toString();
    }

    /**
     * Generates a parameter definition for a type method.
     */
    private String generateParameterDefinition(TypeMethodParameter param, TypeMethodMetadata method) {
        StringBuilder def = new StringBuilder();

        // Parameter mode (IN, OUT, INOUT)
        String inOut = param.getInOut();
        if (inOut != null) {
            if (inOut.contains("IN") && inOut.contains("OUT")) {
                def.append("INOUT ");
            } else if (inOut.contains("OUT")) {
                def.append("OUT ");
            } else {
                def.append("IN ");
            }
        } else {
            def.append("IN ");
        }

        // Parameter name
        String normalizedParamName = PostgresIdentifierNormalizer.normalizeIdentifier(param.getParameterName());
        def.append(normalizedParamName);
        def.append(" ");

        // Parameter type
        String postgresType;
        if (param.isCustomDataType()) {
            String owner = param.getDataTypeOwner().toLowerCase();
            String typeName = param.getDataTypeName().toLowerCase();

            // Check if it's a complex Oracle system type
            if (OracleTypeClassifier.isComplexOracleSystemType(owner, typeName)) {
                postgresType = "jsonb";
                log.debug("Complex Oracle system type '{}.{}' for parameter '{}' will use jsonb",
                        owner, typeName, param.getParameterName());
            } else {
                // User-defined type - use the created PostgreSQL composite type
                postgresType = owner + "." + typeName;
                log.debug("Using composite type '{}' for parameter '{}' in type method '{}'",
                        postgresType, param.getParameterName(), method.getDisplayName());
            }
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            postgresType = TypeConverter.toPostgre(param.getDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown data type '{}' for parameter '{}' in type method '{}', using 'text' as fallback",
                        param.getDataType(), param.getParameterName(), method.getDisplayName());
            }
        }

        def.append(postgresType);

        return def.toString();
    }

    /**
     * Maps Oracle return type to PostgreSQL type.
     */
    private String mapReturnType(TypeMethodMetadata method) {
        if (method.isCustomReturnType()) {
            String owner = method.getReturnTypeOwner().toLowerCase();
            String typeName = method.getReturnTypeName().toLowerCase();

            // Check if it's XMLTYPE
            if (OracleTypeClassifier.isXmlType(owner, typeName)) {
                log.debug("Oracle XMLTYPE return type for type method '{}' mapped to PostgreSQL xml",
                        method.getDisplayName());
                return "xml";
            }

            // Check if it's a complex Oracle system type
            if (OracleTypeClassifier.isComplexOracleSystemType(owner, typeName)) {
                log.debug("Complex Oracle system type '{}.{}' return type for type method '{}' will use jsonb",
                        owner, typeName, method.getDisplayName());
                return "jsonb";
            }

            // User-defined type
            log.debug("Using composite type '{}.{}' as return type for type method '{}'",
                    owner, typeName, method.getDisplayName());
            return owner + "." + typeName;
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            String postgresType = TypeConverter.toPostgre(method.getReturnDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown return data type '{}' for type method '{}', using 'text' as fallback",
                        method.getReturnDataType(), method.getDisplayName());
            }
            return postgresType;
        }
    }

    @Override
    protected String generateSummaryMessage(TypeMethodStubCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Type method stub creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s",
                    String.join(", ", result.getCreatedMethods().stream().limit(5).toList())));
            if (result.getCreatedCount() > 5) {
                summary.append(String.format(" and %d more", result.getCreatedCount() - 5));
            }
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
