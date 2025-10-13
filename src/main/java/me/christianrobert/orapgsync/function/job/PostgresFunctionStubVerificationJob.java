package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.FunctionParameter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * PostgreSQL Function Stub Verification Job.
 *
 * This job extracts function and procedure metadata from the actual PostgreSQL database
 * to verify what function stubs have been successfully created.
 *
 * Unlike OracleFunctionExtractionJob (which reads from Oracle),
 * this job queries the live PostgreSQL database to get the actual function state.
 *
 * The job:
 * 1. Queries PostgreSQL system catalogs (pg_proc, pg_namespace)
 * 2. Extracts all function/procedure metadata
 * 3. Filters out system schemas
 * 4. Returns the list for frontend display and comparison
 */
@Dependent
public class PostgresFunctionStubVerificationJob extends AbstractDatabaseExtractionJob<FunctionMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresFunctionStubVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "FUNCTION_VERIFICATION";
    }

    @Override
    public Class<FunctionMetadata> getResultType() {
        return FunctionMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<FunctionMetadata> results) {
        // Save to PostgreSQL function metadata for verification/display
        stateService.setPostgresFunctionMetadata(results);
    }

    @Override
    protected List<FunctionMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL function verification");

        updateProgress(progressCallback, 10, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected",
                    "Successfully connected to PostgreSQL database");

            List<FunctionMetadata> allFunctions = new ArrayList<>();

            // Extract functions and procedures
            updateProgress(progressCallback, 30, "Extracting functions",
                    "Querying PostgreSQL for functions and procedures");
            allFunctions.addAll(extractFunctionsAndProcedures(postgresConnection));

            // Count by type for summary
            long functionCount = allFunctions.stream().filter(FunctionMetadata::isFunction).count();
            long procedureCount = allFunctions.stream().filter(FunctionMetadata::isProcedure).count();
            long standaloneCount = allFunctions.stream().filter(FunctionMetadata::isStandalone).count();
            long packageMemberCount = allFunctions.stream().filter(FunctionMetadata::isPackageMember).count();

            log.info("Verified {} functions/procedures in PostgreSQL: {} functions, {} procedures; {} standalone, {} package members",
                    allFunctions.size(), functionCount, procedureCount, standaloneCount, packageMemberCount);

            updateProgress(progressCallback, 100, "Complete",
                    String.format("Verified %d functions/procedures: %d functions, %d procedures",
                            allFunctions.size(), functionCount, procedureCount));

            return allFunctions;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "PostgreSQL function verification failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extracts functions and procedures from PostgreSQL.
     */
    private List<FunctionMetadata> extractFunctionsAndProcedures(Connection connection) throws Exception {
        List<FunctionMetadata> functions = new ArrayList<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                p.proname AS function_name,
                pg_get_function_result(p.oid) AS return_type,
                pg_get_function_arguments(p.oid) AS arguments,
                CASE p.prokind
                    WHEN 'f' THEN 'FUNCTION'
                    WHEN 'p' THEN 'PROCEDURE'
                    ELSE 'FUNCTION'
                END AS object_type
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            ORDER BY n.nspname, p.proname
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String functionName = rs.getString("function_name");
                String returnType = rs.getString("return_type");
                String arguments = rs.getString("arguments");
                String objectType = rs.getString("object_type");

                // Filter valid schemas (exclude system schemas)
                if (filterValidSchemas(List.of(schema)).isEmpty()) {
                    continue;
                }

                // Check if this is a package member (contains __)
                String packageName = null;
                String actualFunctionName = functionName;
                if (functionName.contains("__")) {
                    int separatorIndex = functionName.indexOf("__");
                    packageName = functionName.substring(0, separatorIndex);
                    actualFunctionName = functionName.substring(separatorIndex + 2);
                }

                FunctionMetadata function = new FunctionMetadata(schema, actualFunctionName, objectType);
                if (packageName != null) {
                    function.setPackageName(packageName);
                }

                // Set return type for functions
                if ("FUNCTION".equals(objectType) && returnType != null && !returnType.isEmpty()) {
                    function.setReturnDataType(returnType);
                    // Note: We don't track custom return types in verification, just basic extraction
                }

                // Parse arguments (simplified - just count them)
                // Full parameter parsing would be complex, this is sufficient for verification
                if (arguments != null && !arguments.isEmpty() && !arguments.equals("")) {
                    String[] argParts = arguments.split(",");
                    for (int i = 0; i < argParts.length; i++) {
                        String arg = argParts[i].trim();
                        if (!arg.isEmpty()) {
                            // Create simplified parameter (just for display/verification)
                            String paramName = "param" + (i + 1);
                            FunctionParameter param = new FunctionParameter(paramName, i + 1, "unknown", "IN");
                            function.addParameter(param);
                        }
                    }
                }

                functions.add(function);
            }
        }

        log.debug("Extracted {} functions/procedures from PostgreSQL", functions.size());
        return functions;
    }

    @Override
    protected String generateSummaryMessage(List<FunctionMetadata> result) {
        long functionCount = result.stream().filter(FunctionMetadata::isFunction).count();
        long procedureCount = result.stream().filter(FunctionMetadata::isProcedure).count();
        long standaloneCount = result.stream().filter(FunctionMetadata::isStandalone).count();
        long packageMemberCount = result.stream().filter(FunctionMetadata::isPackageMember).count();

        return String.format("Verified %d PostgreSQL functions/procedures: %d functions, %d procedures; %d standalone, %d package members",
                result.size(), functionCount, procedureCount, standaloneCount, packageMemberCount);
    }
}
