package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionImplementationVerificationResult;
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
 * PostgreSQL Standalone Function Implementation Verification Job.
 *
 * This job verifies which standalone functions/procedures have been implemented
 * (as opposed to being stubs). It checks the function body to determine if it's
 * a stub (returns NULL or has empty body) or an actual implementation.
 *
 * Only processes STANDALONE functions (not package members).
 *
 * The job:
 * 1. Queries PostgreSQL system catalogs (pg_proc, pg_namespace)
 * 2. Filters to only standalone functions (no double underscore in name)
 * 3. Checks function bodies to distinguish stubs from implementations
 * 4. Returns detailed result with DDL for frontend display
 */
@Dependent
public class PostgresStandaloneFunctionImplementationVerificationJob extends AbstractDatabaseExtractionJob<FunctionImplementationVerificationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresStandaloneFunctionImplementationVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "STANDALONE_FUNCTION_IMPLEMENTATION_VERIFICATION";
    }

    @Override
    public Class<FunctionImplementationVerificationResult> getResultType() {
        return FunctionImplementationVerificationResult.class;
    }

    @Override
    protected void saveResultsToState(List<FunctionImplementationVerificationResult> results) {
        // Verification results are transient - don't save to state
        // The results are returned directly to frontend for display
    }

    @Override
    protected List<FunctionImplementationVerificationResult> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL standalone function implementation verification");

        updateProgress(progressCallback, 10, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected",
                    "Successfully connected to PostgreSQL database");

            FunctionImplementationVerificationResult result = new FunctionImplementationVerificationResult();

            // Extract standalone functions and procedures (filter out package members)
            updateProgress(progressCallback, 30, "Verifying standalone functions",
                    "Querying PostgreSQL for standalone functions and procedures");
            verifyStandaloneFunctionsAndProcedures(postgresConnection, result);

            log.info("Verified {} standalone functions/procedures in PostgreSQL: {} implemented, {} stubs or failed",
                    result.getVerifiedCount() + result.getFailedCount(),
                    result.getVerifiedCount(), result.getFailedCount());

            updateProgress(progressCallback, 100, "Complete",
                    String.format("Verified %d standalone functions/procedures: %d implemented, %d stubs or failed",
                            result.getVerifiedCount() + result.getFailedCount(),
                            result.getVerifiedCount(), result.getFailedCount()));

            List<FunctionImplementationVerificationResult> resultList = new ArrayList<>();
            resultList.add(result);
            return resultList;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "PostgreSQL standalone function implementation verification failed: " + e.getMessage());
            log.error("PostgreSQL standalone function implementation verification failed", e);
            throw e;
        }
    }

    /**
     * Verifies standalone functions and procedures from PostgreSQL.
     * Excludes package members (functions with __ in name).
     * Populates the result object with verified/failed functions and their DDL.
     */
    private void verifyStandaloneFunctionsAndProcedures(Connection connection,
                                                       FunctionImplementationVerificationResult result) throws Exception {
        String sql = """
            SELECT
                n.nspname AS schema,
                p.proname AS name,
                pg_get_functiondef(p.oid) AS function_definition,
                CASE p.prokind
                    WHEN 'f' THEN 'FUNCTION'
                    WHEN 'p' THEN 'PROCEDURE'
                    ELSE 'FUNCTION'
                END AS object_type,
                pg_get_function_result(p.oid) AS return_type,
                pg_get_function_arguments(p.oid) AS arguments
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'oracle_compat')
              AND n.nspname NOT LIKE 'pg_%'
              AND p.proname NOT LIKE '%__%'  -- Exclude package members (have __ in name)
            ORDER BY n.nspname, p.proname
            """;

        // First, let's see what schemas and functions we have (diagnostic)
        String diagnosticSql = """
            SELECT
                n.nspname AS schema,
                COUNT(*) AS function_count,
                COUNT(CASE WHEN p.proname LIKE '%__%' THEN 1 END) AS package_member_count,
                COUNT(CASE WHEN p.proname NOT LIKE '%__%' THEN 1 END) AS standalone_count
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast', 'oracle_compat')
              AND n.nspname NOT LIKE 'pg_%'
            GROUP BY n.nspname
            ORDER BY n.nspname
            """;

        log.info("Running diagnostic query to see available functions");
        try (PreparedStatement diagPs = connection.prepareStatement(diagnosticSql);
             ResultSet diagRs = diagPs.executeQuery()) {
            while (diagRs.next()) {
                log.info("Schema: {}, Total functions: {}, Package members (with __): {}, Standalone: {}",
                        diagRs.getString("schema"),
                        diagRs.getInt("function_count"),
                        diagRs.getInt("package_member_count"),
                        diagRs.getInt("standalone_count"));
            }
        } catch (Exception e) {
            log.warn("Diagnostic query failed (non-fatal)", e);
        }

        log.info("Executing verification query for standalone functions");

        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            int rowCount = 0;
            while (rs.next()) {
                rowCount++;
                String schema = rs.getString("schema");
                String name = rs.getString("name");
                String objectType = rs.getString("object_type");
                String functionDefinition = rs.getString("function_definition");
                String returnType = rs.getString("return_type");
                String arguments = rs.getString("arguments");

                // Build human-readable signature
                String signature = buildSignature(name, arguments, returnType, objectType);

                // Check if this is a stub or implemented
                boolean isStub = isStubImplementation(functionDefinition);

                if (isStub) {
                    // Mark as failed (stub not implemented)
                    result.addFailedFunction(schema, name, objectType, returnType,
                            functionDefinition, true, signature,
                            "Function is still a stub (not implemented)");
                    log.debug("Function {}.{} is a stub", schema, name);
                } else {
                    // Mark as verified (implemented)
                    result.addVerifiedFunction(schema, name, objectType, returnType,
                            functionDefinition, false, signature);
                    log.debug("Function {}.{} is implemented", schema, name);
                }
            }

            log.info("Verification query returned {} rows. Verified: {}, Failed: {}",
                    rowCount, result.getVerifiedCount(), result.getFailedCount());

            if (rowCount == 0) {
                log.warn("No standalone functions found in database. Check that functions exist and don't have '__' in their names.");
            }
        } catch (Exception e) {
            log.error("Error during function verification query execution", e);
            throw e;
        }
    }

    /**
     * Builds a human-readable function signature for display.
     */
    private String buildSignature(String name, String arguments, String returnType, String objectType) {
        StringBuilder sig = new StringBuilder();
        sig.append(name).append("(");

        if (arguments != null && !arguments.isEmpty()) {
            sig.append(arguments);
        }

        sig.append(")");

        if ("FUNCTION".equals(objectType) && returnType != null) {
            sig.append(" → ").append(returnType);
        }

        return sig.toString();
    }

    /**
     * Determines if a function is a stub implementation.
     * Stubs typically:
     * - Return NULL for functions
     * - Have empty body or just RETURN for procedures
     * - Contain comment "-- Stub implementation"
     */
    private boolean isStubImplementation(String functionDefinition) {
        if (functionDefinition == null || functionDefinition.isEmpty()) {
            return false;
        }

        String body = functionDefinition.toLowerCase();

        // Check for stub indicators
        boolean hasStubComment = body.contains("stub implementation") ||
                                 body.contains("stub created by") ||
                                 body.contains("replace with actual implementation");

        boolean hasNullReturn = body.contains("return null") && !body.contains("if") && !body.contains("case");

        boolean hasEmptyBody = body.contains("begin") && body.contains("end") &&
                               !body.contains("declare") &&
                               (body.indexOf("end") - body.indexOf("begin") < 100);

        return hasStubComment || hasNullReturn || hasEmptyBody;
    }

    @Override
    protected String generateSummaryMessage(List<FunctionImplementationVerificationResult> results) {
        if (results.isEmpty()) {
            return "No verification results";
        }

        FunctionImplementationVerificationResult result = results.get(0);
        return String.format("Verification completed: %d implemented, %d stubs or failed",
                result.getVerifiedCount(), result.getFailedCount());
    }
}
