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
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;
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
 * <p>Function/procedure stubs are created with empty implementations to support future
 * view migration and PL/SQL code transformation. They have correct signatures but
 * return NULL.
 *
 * <p><strong>Important:</strong> Both Oracle functions and procedures are created as
 * PostgreSQL FUNCTIONs (not PROCEDUREs) to ensure compatibility with the transformation
 * layer and enable CREATE OR REPLACE when stubs are replaced with actual implementations.
 *
 * <p>Oracle procedures are mapped to PostgreSQL functions with RETURNS clause based on
 * OUT parameters:
 * <ul>
 *   <li>No OUT/INOUT parameters → RETURNS void</li>
 *   <li>Single OUT/INOUT parameter → RETURNS &lt;type&gt;</li>
 *   <li>Multiple OUT/INOUT parameters → RETURNS RECORD</li>
 * </ul>
 *
 * <p>Naming convention for package members: packagename__functionname (double underscore)
 */
@Dependent
public class PostgresFunctionStubCreationJob extends AbstractDatabaseWriteJob<FunctionStubCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresFunctionStubCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    /**
     * Metadata indices, built once per run, and one {@link TransformationContext} per schema on
     * top of them.
     *
     * <p>A stub signature and the implementation that replaces it are one signature — PostgreSQL
     * replaces the stub only on exact agreement — so both sides have to answer "what PostgreSQL
     * type is this Oracle type?" identically. The implementation side has always asked
     * {@link TypeResolver}. This job used to ask {@link me.christianrobert.orapgsync.core.tools.TypeConverter}
     * directly, which has no metadata and echoes back anything it does not recognise, so the two
     * sides could not agree on any user-defined type. Sharing the resolver is what makes them one
     * answer rather than two.
     */
    private TransformationIndices indices;
    private final Map<String, TransformationContext> schemaContexts = new HashMap<>();

    /**
     * Raised when no metadata can place a type, so the stub cannot be built.
     *
     * <p>Deliberately not a fallback to {@code text} or to the bare Oracle name: the first makes
     * the stub disagree with the implementation, which is the silent-overwrite family; the second
     * is what produced {@code schema "uicomponent" does not exist}, an error naming the symptom
     * rather than the type.
     */
    static class TypeResolutionException extends RuntimeException {
        TypeResolutionException(String message) {
            super(message);
        }
    }

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

        // Build the metadata indices the type resolution below reads. Built once: the same index
        // serves every schema, and only the per-schema resolution context differs.
        List<String> schemas = validOracleFunctions.stream()
                .map(FunctionMetadata::getSchema)
                .distinct()
                .toList();
        useIndices(MetadataIndexBuilder.build(stateService, schemas));

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
            } catch (TypeResolutionException e) {
                // No SQL to record: the signature could not be built, so none was ever generated.
                // The message names the type, which is the thing the next run needs to rank.
                String errorMessage = String.format("Failed to create %s stub '%s': %s",
                        function.isFunction() ? "function" : "procedure",
                        qualifiedFunctionName, e.getMessage());
                result.addError(qualifiedFunctionName, errorMessage, null);
                log.error("Failed to create {} stub '{}': {}",
                        function.isFunction() ? "function" : "procedure",
                        qualifiedFunctionName, e.getMessage());
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
     *
     * <p>Note: Both Oracle functions and procedures are created as PostgreSQL FUNCTIONs
     * (not PROCEDUREs) for consistency with the transformation layer and to support
     * CREATE OR REPLACE when transformations are applied.
     *
     * <p>Functions return NULL, procedures have empty body with appropriate RETURNS clause
     * based on OUT parameters.
     */
    String generateCreateFunctionStubSQL(FunctionMetadata function) {
        StringBuilder sql = new StringBuilder();

        // Always create as FUNCTION (not PROCEDURE) for replaceability
        sql.append("CREATE OR REPLACE FUNCTION ");
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

        // Add RETURNS clause (required for all FUNCTIONs)
        sql.append(" RETURNS ");
        if (function.isFunction()) {
            // Oracle function - use explicit return type
            sql.append(mapReturnType(function));
        } else {
            // Oracle procedure - calculate based on OUT parameters
            sql.append(calculateProcedureReturnsClause(function));
        }

        sql.append(" AS $$\n");
        sql.append("BEGIN\n");

        // Add stub body with appropriate RETURN statement
        if (function.isFunction()) {
            // Oracle function - use RETURN NULL for explicit return types
            sql.append("    RETURN NULL; -- Stub: Original Oracle function ");
        } else {
            // Oracle procedure → PostgreSQL function with OUT parameters or void
            // Use RETURN; (no value) for functions with OUT parameters or returning void
            sql.append("    RETURN; -- Stub: Original Oracle procedure ");
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
     *
     * <p>Format: param_name [MODE] type
     * <p>Mode is only added for OUT and INOUT parameters (IN is default in PostgreSQL).
     * <p>This matches the format used by the transformation layer for consistency.
     */
    private String generateParameterDefinition(FunctionParameter param, FunctionMetadata function) {
        StringBuilder def = new StringBuilder();

        // Parameter name (comes first in PostgreSQL)
        String normalizedParamName = PostgresIdentifierNormalizer.normalizeIdentifier(param.getParameterName());
        def.append(normalizedParamName);
        def.append(" ");

        // Parameter mode (IN, OUT, INOUT) - only add for OUT and INOUT
        String inOut = param.getInOut();
        if (inOut != null) {
            if (inOut.contains("IN") && inOut.contains("OUT")) {
                def.append("INOUT ");
            } else if (inOut.contains("OUT")) {
                def.append("OUT ");
            }
            // IN is default in PostgreSQL, don't add keyword
        }
        // If inOut is null, it's an IN parameter (default)

        // Parameter type
        def.append(resolveParameterType(param, function));

        return def.toString();
    }

    /**
     * The PostgreSQL type for one parameter, via the single type-emission authority.
     *
     * @throws TypeResolutionException if no metadata can place the type
     */
    private String resolveParameterType(FunctionParameter param, FunctionMetadata function) {
        if (param.isCustomDataType()) {
            String owner = param.getDataTypeOwner().toLowerCase();
            String typeName = param.getDataTypeName().toLowerCase();

            // A complex Oracle system type is decided without metadata — it is the same answer in
            // every schema — so it is settled before the resolver is consulted.
            if (OracleTypeClassifier.isComplexOracleSystemType(owner, typeName)) {
                return "jsonb";
            }
            return resolveOrRefuse(owner + "." + typeName, function,
                    "parameter '" + param.getParameterName() + "'");
        }

        return resolveOrRefuse(param.getDataType(), function,
                "parameter '" + param.getParameterName() + "'");
    }

    /**
     * Resolves an Oracle type against this function's schema, or refuses.
     *
     * @throws TypeResolutionException if neither metadata nor the built-in mapping can place it
     */
    private String resolveOrRefuse(String oracleType, FunctionMetadata function, String position) {
        String resolved = TypeResolver.toPostgresOrNull(oracleType, contextFor(function.getSchema()));
        if (resolved == null) {
            throw new TypeResolutionException(String.format(
                    "cannot resolve Oracle type '%s' for %s", oracleType, position));
        }
        return resolved;
    }

    /**
     * Installs the metadata indices and discards any contexts built on the previous set.
     *
     * <p>Package-private so a test can drive the real signature generation against hand-built
     * metadata. The stub signature has to agree with the implementation signature exactly, and
     * the two are derived from different inputs, so the only thing that can hold them together is
     * a test that builds both — this is the seam that lets one exist.
     */
    void useIndices(TransformationIndices indices) {
        this.indices = indices;
        this.schemaContexts.clear();
    }

    /**
     * The resolution context for a schema, created on first use.
     *
     * <p>Only the schema varies. A stub carries no package variables and no enclosing routine, so
     * the remaining context slots are genuinely empty here rather than merely unpopulated.
     */
    private TransformationContext contextFor(String schema) {
        return schemaContexts.computeIfAbsent(schema.toLowerCase(),
                s -> new TransformationContext(s, indices, new SimpleTypeEvaluator(s, indices)));
    }

    /**
     * Calculates RETURNS clause for Oracle procedures based on OUT parameters.
     *
     * <p>Rules:
     * <ul>
     *   <li>No OUT/INOUT → RETURNS void</li>
     *   <li>Single OUT/INOUT → RETURNS &lt;type&gt;</li>
     *   <li>Multiple OUT/INOUT → RETURNS RECORD</li>
     * </ul>
     *
     * @param function Function metadata (must be a procedure)
     * @return PostgreSQL RETURNS clause type (void, type, or RECORD)
     */
    private String calculateProcedureReturnsClause(FunctionMetadata function) {
        // Count OUT and INOUT parameters
        int outParamCount = 0;
        String singleOutParamType = null;

        for (FunctionParameter param : function.getParameters()) {
            String inOut = param.getInOut();
            if (inOut != null && inOut.contains("OUT")) {
                outParamCount++;
                if (outParamCount == 1) {
                    // Store type of first OUT parameter
                    singleOutParamType = resolveParameterType(param, function);
                }
            }
        }

        if (outParamCount == 0) {
            return "void";
        } else if (outParamCount == 1) {
            return singleOutParamType != null ? singleOutParamType : "text";
        } else {
            return "RECORD";
        }
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

            return resolveOrRefuse(owner + "." + typeName, function, "return type");
        } else {
            return resolveOrRefuse(function.getReturnDataType(), function, "return type");
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
