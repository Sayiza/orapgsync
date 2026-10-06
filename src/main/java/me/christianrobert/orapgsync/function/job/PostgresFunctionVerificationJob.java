package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionVerificationResult;
import me.christianrobert.orapgsync.core.job.model.function.FunctionVerificationResult.FunctionInfo;
import me.christianrobert.orapgsync.core.job.model.function.FunctionVerificationResult.FunctionStatus;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unified PostgreSQL function verification job.
 *
 * Verifies all PostgreSQL functions (both stubs and implementations) by:
 * 1. Querying PostgreSQL directly (no state dependency)
 * 2. Extracting function DDL using pg_get_functiondef()
 * 3. Auto-detecting status (STUB vs IMPLEMENTED) from function body content
 * 4. Grouping results by schema for easy navigation
 * 5. Handling both standalone and package functions (with __ naming convention)
 *
 * This job does NOT execute functions for performance and safety reasons.
 * The DDL is provided for manual inspection in the frontend.
 */
@Dependent
public class PostgresFunctionVerificationJob extends AbstractDatabaseExtractionJob<FunctionVerificationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresFunctionVerificationJob.class);

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
    public Class<FunctionVerificationResult> getResultType() {
        return FunctionVerificationResult.class;
    }

    @Override
    protected void saveResultsToState(List<FunctionVerificationResult> results) {
        // This is a verification job - results are not saved to state
        // They are returned directly to the user for review
        if (!results.isEmpty()) {
            FunctionVerificationResult result = results.get(0);
            log.info("Function verification completed: {} total functions ({} implemented, {} stubs, {} errors) across {} schemas",
                    result.getTotalFunctions(), result.getImplementedCount(), result.getStubCount(),
                    result.getErrorCount(), result.getSchemas().size());
        }
    }

    @Override
    protected List<FunctionVerificationResult> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting unified PostgreSQL function verification");

        updateProgress(progressCallback, 0, "Initializing", "Starting function verification");

        FunctionVerificationResult result = new FunctionVerificationResult();

        updateProgress(progressCallback, 10, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 15, "Connected", "Successfully connected to PostgreSQL database");

            // Query all functions from PostgreSQL directly (no state dependency)
            List<FunctionEntry> allFunctions = queryAllFunctions(pgConnection);

            if (allFunctions.isEmpty()) {
                log.warn("No functions found in PostgreSQL database");
                updateProgress(progressCallback, 100, "Completed", "No functions to verify");
                return List.of(result);
            }

            log.info("Found {} functions across all schemas, extracting DDL", allFunctions.size());

            int totalFunctions = allFunctions.size();
            int processedFunctions = 0;

            for (FunctionEntry entry : allFunctions) {
                String schema = entry.schema;
                String functionName = entry.functionName;
                String functionType = entry.functionType;
                String qualifiedName = schema + "." + functionName;

                updateProgress(progressCallback,
                        15 + (processedFunctions * 70 / totalFunctions),
                        "Processing function: " + qualifiedName,
                        String.format("Function %d of %d", processedFunctions + 1, totalFunctions));

                boolean isPackageMember = functionName.contains("__");
                FunctionInfo functionInfo = new FunctionInfo(functionName, functionType, isPackageMember);

                try {
                    // Extract function DDL
                    String ddl = extractFunctionDdl(pgConnection, schema, functionName);

                    if (ddl == null || ddl.trim().isEmpty()) {
                        functionInfo.setStatus(FunctionStatus.ERROR);
                        functionInfo.setErrorMessage("Could not retrieve function DDL");
                        log.warn("Could not retrieve DDL for function: {}", qualifiedName);
                    } else {
                        // Auto-detect status from DDL content
                        FunctionStatus status = detectStatusFromDdl(ddl);
                        functionInfo.setStatus(status);
                        functionInfo.setFunctionDdl(ddl);

                        log.debug("Function {}: type = {}, package = {}, status = {}",
                                qualifiedName, functionType, isPackageMember, status);
                    }

                } catch (Exception e) {
                    log.error("Failed to process function: " + qualifiedName, e);
                    functionInfo.setStatus(FunctionStatus.ERROR);
                    functionInfo.setErrorMessage("Failed to extract DDL: " + e.getMessage());
                }

                result.addFunction(schema, functionInfo);
                processedFunctions++;
            }

            updateProgress(progressCallback, 90, "Finalizing", "Generating verification report");

            log.info("Function verification completed: {} total functions ({} implemented, {} stubs, {} errors) across {} schemas",
                    result.getTotalFunctions(), result.getImplementedCount(), result.getStubCount(),
                    result.getErrorCount(), result.getSchemas().size());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Verified %d functions: %d implemented, %d stubs, %d errors",
                            result.getTotalFunctions(), result.getImplementedCount(),
                            result.getStubCount(), result.getErrorCount()));

            return List.of(result);

        } catch (Exception e) {
            log.error("Function verification failed", e);
            updateProgress(progressCallback, -1, "Failed", "Verification failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Queries all functions from PostgreSQL database.
     * Returns schema, function name, and function type (FUNCTION or PROCEDURE).
     * Excludes system schemas.
     */
    private List<FunctionEntry> queryAllFunctions(Connection pgConnection) throws SQLException {
        List<FunctionEntry> functions = new ArrayList<>();

        String sql = """
            SELECT
                n.nspname AS schema,
                p.proname AS function_name,
                CASE p.prokind
                    WHEN 'f' THEN 'FUNCTION'
                    WHEN 'p' THEN 'PROCEDURE'
                    ELSE 'FUNCTION'
                END AS function_type
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
              AND n.nspname NOT LIKE 'pg_%'
            ORDER BY n.nspname, p.proname
            """;

        try (PreparedStatement ps = pgConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema");
                String functionName = rs.getString("function_name");
                String functionType = rs.getString("function_type");
                functions.add(new FunctionEntry(schema, functionName, functionType));
            }
        }

        log.info("Found {} functions in PostgreSQL database", functions.size());
        return functions;
    }

    /**
     * Extracts function DDL using pg_get_functiondef().
     * The second parameter (true) enables pretty-printing for readability.
     */
    private String extractFunctionDdl(Connection pgConnection, String schema, String functionName) throws SQLException {
        // Use pg_get_functiondef with pretty-printing enabled
        String sql = "SELECT pg_get_functiondef(p.oid) AS functiondef " +
                     "FROM pg_proc p " +
                     "JOIN pg_namespace n ON p.pronamespace = n.oid " +
                     "WHERE n.nspname = ? AND p.proname = ?";

        try (PreparedStatement ps = pgConnection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, functionName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("functiondef");
                }
            }
        }

        return null;
    }

    /**
     * Auto-detects function status from DDL content.
     *
     * STUB: Contains stub patterns (RETURN NULL, empty body, stub comments)
     * IMPLEMENTED: Full function implementation with actual logic
     */
    private FunctionStatus detectStatusFromDdl(String ddl) {
        if (ddl == null || ddl.trim().isEmpty()) {
            return FunctionStatus.ERROR;
        }

        String normalized = ddl.toLowerCase();

        // Check for stub indicators
        boolean hasStubComment = normalized.contains("stub implementation") ||
                                 normalized.contains("stub created by") ||
                                 normalized.contains("replace with actual implementation") ||
                                 normalized.contains("-- stub");

        // Check for simple RETURN NULL pattern (without complex logic)
        boolean hasSimpleReturnNull = normalized.contains("return null") &&
                                       !normalized.contains("if ") &&
                                       !normalized.contains("case ") &&
                                       !normalized.contains("loop");

        // Check for simple RETURN; pattern for procedures (without complex logic)
        boolean hasSimpleReturn = normalized.matches(".*\\breturn\\s*;.*") &&
                                  !normalized.contains("if ") &&
                                  !normalized.contains("case ") &&
                                  !normalized.contains("loop") &&
                                  !normalized.contains("return null");

        // Check for very short body (likely a stub)
        boolean hasShortBody = normalized.contains("begin") && normalized.contains("end") &&
                               (normalized.indexOf("end") - normalized.indexOf("begin") < 150);

        if (hasStubComment || (hasSimpleReturnNull && hasShortBody) || (hasSimpleReturn && hasShortBody)) {
            return FunctionStatus.STUB;
        }

        return FunctionStatus.IMPLEMENTED;
    }

    @Override
    protected String generateSummaryMessage(List<FunctionVerificationResult> results) {
        if (results == null || results.isEmpty()) {
            return "Function verification completed: No functions verified";
        }

        FunctionVerificationResult result = results.get(0);
        return String.format("Function verification completed: %d total (%d implemented, %d stubs, %d errors)",
                result.getTotalFunctions(), result.getImplementedCount(),
                result.getStubCount(), result.getErrorCount());
    }

    /**
     * Simple holder for schema + function name + type tuples.
     */
    private static class FunctionEntry {
        final String schema;
        final String functionName;
        final String functionType;

        FunctionEntry(String schema, String functionName, String functionType) {
            this.schema = schema;
            this.functionName = functionName;
            this.functionType = functionType;
        }
    }
}
