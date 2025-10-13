package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.core.job.model.function.FunctionStubCreationResult;
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
 * Creates function and procedure stubs in PostgreSQL database.
 *
 * Function/procedure stubs are created with empty implementations to support future
 * view migration and PL/SQL code transformation. They have correct signatures but
 * return NULL (functions) or have empty body (procedures).
 *
 * Naming convention for package members: packagename__functionname (double underscore)
 */
@Dependent
public class PostgresFunctionStubCreationJob extends AbstractDatabaseWriteJob<FunctionStubCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresFunctionStubCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "FUNCTION_STUB_CREATION";
    }

    @Override
    public Class<FunctionStubCreationResult> getResultType() {
        return FunctionStubCreationResult.class;
    }

    @Override
    protected void saveResultsToState(FunctionStubCreationResult result) {
        stateService.setFunctionStubCreationResult(result);
    }

    @Override
    protected FunctionStubCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL function/procedure stub creation process");

        // Get Oracle functions from state
        List<FunctionMetadata> oracleFunctions = getOracleFunctions();
        if (oracleFunctions.isEmpty()) {
            updateProgress(progressCallback, 100, "No functions to process",
                    "No Oracle functions found in state. Please extract Oracle functions first.");
            log.warn("No Oracle functions found in state for function stub creation");
            return new FunctionStubCreationResult();
        }

        // Filter valid functions (exclude system schemas)
        List<FunctionMetadata> validOracleFunctions = filterValidFunctions(oracleFunctions);

        updateProgress(progressCallback, 10, "Analyzing functions",
                String.format("Found %d Oracle functions/procedures, %d are valid for creation",
                        oracleFunctions.size(), validOracleFunctions.size()));

        FunctionStubCreationResult result = new FunctionStubCreationResult();

        if (validOracleFunctions.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid functions",
                    "No valid Oracle functions to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL functions/procedures
            Set<String> existingPostgresFunctions = getExistingPostgresFunctions(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing functions",
                    String.format("Found %d existing PostgreSQL functions/procedures", existingPostgresFunctions.size()));

            // Determine which functions need to be created
            List<FunctionMetadata> functionsToCreate = new ArrayList<>();
            List<FunctionMetadata> functionsAlreadyExisting = new ArrayList<>();

            for (FunctionMetadata function : validOracleFunctions) {
                String qualifiedFunctionName = function.getQualifiedName();
                if (existingPostgresFunctions.contains(qualifiedFunctionName.toLowerCase())) {
                    functionsAlreadyExisting.add(function);
                } else {
                    functionsToCreate.add(function);
                }
            }

            // Mark already existing functions as skipped
            for (FunctionMetadata function : functionsAlreadyExisting) {
                String qualifiedFunctionName = function.getQualifiedName();
                result.addSkippedFunction(qualifiedFunctionName);
                log.debug("Function '{}' already exists in PostgreSQL, skipping", qualifiedFunctionName);
            }

            updateProgress(progressCallback, 40, "Planning creation",
                    String.format("%d functions/procedures to create, %d already exist",
                            functionsToCreate.size(), functionsAlreadyExisting.size()));

            if (functionsToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All functions exist",
                        "All Oracle functions already exist in PostgreSQL");
                return result;
            }

            // Create function stubs
            createFunctionStubs(postgresConnection, functionsToCreate, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d function stubs, skipped %d existing, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Function stub creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<FunctionMetadata> getOracleFunctions() {
        return stateService.getOracleFunctionMetadata();
    }

    private List<FunctionMetadata> filterValidFunctions(List<FunctionMetadata> functions) {
        List<FunctionMetadata> validFunctions = new ArrayList<>();
        for (FunctionMetadata function : functions) {
            if (!filterValidSchemas(List.of(function.getSchema())).isEmpty()) {
                validFunctions.add(function);
            }
        }
        return validFunctions;
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

    private void createFunctionStubs(Connection connection, List<FunctionMetadata> functions,
                                      FunctionStubCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalFunctions = functions.size();
        int processedFunctions = 0;

        for (FunctionMetadata function : functions) {
            int progressPercentage = 40 + (processedFunctions * 50 / totalFunctions);
            String qualifiedFunctionName = function.getQualifiedName();
            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating %s stub: %s",
                            function.isFunction() ? "function" : "procedure",
                            qualifiedFunctionName),
                    String.format("%s %d of %d",
                            function.isFunction() ? "Function" : "Procedure",
                            processedFunctions + 1, totalFunctions));

            try {
                createFunctionStub(connection, function);
                result.addCreatedFunction(qualifiedFunctionName);
                log.debug("Successfully created PostgreSQL {} stub: {}",
                        function.isFunction() ? "function" : "procedure", qualifiedFunctionName);
            } catch (SQLException e) {
                String sqlStatement = generateCreateFunctionStubSQL(function);
                String errorMessage = String.format("Failed to create %s stub '%s': %s",
                        function.isFunction() ? "function" : "procedure",
                        qualifiedFunctionName, e.getMessage());
                result.addError(qualifiedFunctionName, errorMessage, sqlStatement);
                log.error("Failed to create {} stub '{}': {}",
                        function.isFunction() ? "function" : "procedure",
                        qualifiedFunctionName, e.getMessage());
                log.error("Failed SQL statement: {}", sqlStatement);
            }

            processedFunctions++;
        }
    }

    private void createFunctionStub(Connection connection, FunctionMetadata function) throws SQLException {
        String sql = generateCreateFunctionStubSQL(function);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    /**
     * Generates SQL for creating a function or procedure stub.
     * Functions return NULL, procedures have empty body.
     */
    private String generateCreateFunctionStubSQL(FunctionMetadata function) {
        StringBuilder sql = new StringBuilder();

        sql.append("CREATE OR REPLACE ");
        sql.append(function.isFunction() ? "FUNCTION " : "PROCEDURE ");
        sql.append(function.getSchema().toLowerCase());
        sql.append(".");
        sql.append(function.getPostgresName());
        sql.append("(");

        // Add parameters
        List<String> paramDefinitions = new ArrayList<>();
        for (FunctionParameter param : function.getParameters()) {
            String paramDef = generateParameterDefinition(param, function);
            paramDefinitions.add(paramDef);
        }
        sql.append(String.join(", ", paramDefinitions));
        sql.append(")");

        // Add return type for functions
        if (function.isFunction()) {
            sql.append(" RETURNS ");
            sql.append(mapReturnType(function));
        }

        sql.append(" AS $$\n");
        sql.append("BEGIN\n");

        if (function.isFunction()) {
            sql.append("    RETURN NULL; -- Stub: Original Oracle function ");
        } else {
            sql.append("    -- Stub: Original Oracle procedure ");
        }

        // Add comment about original location
        if (function.isPackageMember()) {
            sql.append(function.getSchema().toUpperCase());
            sql.append(".");
            sql.append(function.getPackageName().toUpperCase());
            sql.append(".");
            sql.append(function.getObjectName().toUpperCase());
        } else {
            sql.append(function.getSchema().toUpperCase());
            sql.append(".");
            sql.append(function.getObjectName().toUpperCase());
        }

        sql.append("\n");
        sql.append("END;\n");
        sql.append("$$ LANGUAGE plpgsql");

        return sql.toString();
    }

    /**
     * Generates a parameter definition for a function/procedure.
     */
    private String generateParameterDefinition(FunctionParameter param, FunctionMetadata function) {
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
                log.debug("Using composite type '{}' for parameter '{}' in function '{}'",
                        postgresType, param.getParameterName(), function.getDisplayName());
            }
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            postgresType = TypeConverter.toPostgre(param.getDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown data type '{}' for parameter '{}' in function '{}', using 'text' as fallback",
                        param.getDataType(), param.getParameterName(), function.getDisplayName());
            }
        }

        def.append(postgresType);

        return def.toString();
    }

    /**
     * Maps Oracle return type to PostgreSQL type.
     */
    private String mapReturnType(FunctionMetadata function) {
        if (function.isCustomReturnType()) {
            String owner = function.getReturnTypeOwner().toLowerCase();
            String typeName = function.getReturnTypeName().toLowerCase();

            // Check if it's XMLTYPE
            if (OracleTypeClassifier.isXmlType(owner, typeName)) {
                log.debug("Oracle XMLTYPE return type for function '{}' mapped to PostgreSQL xml",
                        function.getDisplayName());
                return "xml";
            }

            // Check if it's a complex Oracle system type
            if (OracleTypeClassifier.isComplexOracleSystemType(owner, typeName)) {
                log.debug("Complex Oracle system type '{}.{}' return type for function '{}' will use jsonb",
                        owner, typeName, function.getDisplayName());
                return "jsonb";
            }

            // User-defined type
            log.debug("Using composite type '{}.{}' as return type for function '{}'",
                    owner, typeName, function.getDisplayName());
            return owner + "." + typeName;
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            String postgresType = TypeConverter.toPostgre(function.getReturnDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown return data type '{}' for function '{}', using 'text' as fallback",
                        function.getReturnDataType(), function.getDisplayName());
            }
            return postgresType;
        }
    }

    @Override
    protected String generateSummaryMessage(FunctionStubCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Function stub creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s",
                    String.join(", ", result.getCreatedFunctions())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s",
                    String.join(", ", result.getSkippedFunctions())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
