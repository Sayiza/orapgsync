package me.christianrobert.orapgsync.typemethod.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.function.Consumer;

/**
 * Verifies that type method stubs were created successfully in PostgreSQL.
 * Compares the created stubs against the original Oracle type methods.
 */
@Dependent
public class PostgresTypeMethodStubVerificationJob extends AbstractDatabaseExtractionJob<TypeMethodMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTypeMethodStubVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "TYPE_METHOD_STUB_VERIFICATION";
    }

    @Override
    public Class<TypeMethodMetadata> getResultType() {
        return TypeMethodMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TypeMethodMetadata> results) {
        // Verification results are not saved to state, only returned
        log.info("Type method stub verification completed: {} methods verified", results.size());
    }

    @Override
    protected List<TypeMethodMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting type method stub verification");

        // Get Oracle type methods from state
        List<TypeMethodMetadata> oracleTypeMethods = stateService.getOracleTypeMethodMetadata();
        if (oracleTypeMethods.isEmpty()) {
            updateProgress(progressCallback, 100, "No type methods to verify",
                    "No Oracle type methods found in state");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 10, "Connecting to PostgreSQL",
                "Establishing database connection");

        List<TypeMethodMetadata> verifiedMethods = new ArrayList<>();
        List<String> missingMethods = new ArrayList<>();

        try (Connection connection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL functions
            Set<String> existingFunctions = getExistingPostgresFunctions(connection);

            updateProgress(progressCallback, 30, "Verifying type methods",
                    String.format("Checking %d type methods", oracleTypeMethods.size()));

            int totalMethods = oracleTypeMethods.size();
            int processedMethods = 0;

            for (TypeMethodMetadata method : oracleTypeMethods) {
                int progressPercentage = 30 + (processedMethods * 60 / totalMethods);
                updateProgress(progressCallback, progressPercentage,
                        "Verifying type method: " + method.getQualifiedName(),
                        String.format("Method %d of %d", processedMethods + 1, totalMethods));

                String qualifiedName = method.getQualifiedName().toLowerCase();
                if (existingFunctions.contains(qualifiedName)) {
                    verifiedMethods.add(method);
                    log.debug("Verified type method stub exists: {}", qualifiedName);
                } else {
                    missingMethods.add(qualifiedName);
                    log.warn("Type method stub missing: {}", qualifiedName);
                }

                processedMethods++;
            }

            updateProgress(progressCallback, 90, "Verification complete",
                    String.format("Verified %d type method stubs, %d missing",
                            verifiedMethods.size(), missingMethods.size()));

            if (!missingMethods.isEmpty()) {
                log.warn("Missing type method stubs: {}", String.join(", ", missingMethods));
            }

            return verifiedMethods;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Type method stub verification failed: " + e.getMessage());
            throw e;
        }
    }

    private Set<String> getExistingPostgresFunctions(Connection connection) throws Exception {
        Set<String> functions = new HashSet<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                p.proname AS function_name
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            ORDER BY n.nspname, p.proname
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

        log.debug("Found {} existing PostgreSQL functions", functions.size());
        return functions;
    }

    @Override
    protected String generateSummaryMessage(List<TypeMethodMetadata> results) {
        int totalOracleMethods = stateService.getOracleTypeMethodMetadata().size();
        int verifiedMethods = results.size();
        int missingMethods = totalOracleMethods - verifiedMethods;

        return String.format("Verification completed: %d of %d type method stubs verified, %d missing",
                verifiedMethods, totalOracleMethods, missingMethods);
    }
}
