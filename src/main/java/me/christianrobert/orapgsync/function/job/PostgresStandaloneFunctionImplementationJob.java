package me.christianrobert.orapgsync.function.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import me.christianrobert.orapgsync.core.job.model.function.StandaloneFunctionImplementationResult;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * PostgreSQL Standalone Function Implementation Job.
 *
 * This job implements standalone functions/procedures by transforming Oracle PL/SQL
 * to PostgreSQL PL/pgSQL and replacing the stub implementations with actual logic.
 *
 * Only processes STANDALONE functions (not package members).
 *
 * The job:
 * 1. Retrieves Oracle standalone function metadata from state
 * 2. For each standalone function:
 *    a. Extracts Oracle PL/SQL source from ALL_SOURCE
 *    b. Transforms PL/SQL → PL/pgSQL using ANTLR (TODO: to be implemented)
 *    c. Executes CREATE OR REPLACE FUNCTION/PROCEDURE in PostgreSQL
 * 3. Returns results tracking success/skipped/errors
 *
 * NOTE: This is currently a STUB implementation. The actual PL/SQL transformation
 * logic will be added in follow-up steps.
 */
@Dependent
public class PostgresStandaloneFunctionImplementationJob extends AbstractDatabaseWriteJob<StandaloneFunctionImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresStandaloneFunctionImplementationJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private TransformationService transformationService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "STANDALONE_FUNCTION_IMPLEMENTATION";
    }

    @Override
    public Class<StandaloneFunctionImplementationResult> getResultType() {
        return StandaloneFunctionImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(StandaloneFunctionImplementationResult result) {
        stateService.setStandaloneFunctionImplementationResult(result);
    }

    @Override
    protected StandaloneFunctionImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        StandaloneFunctionImplementationResult result = new StandaloneFunctionImplementationResult();

        updateProgress(progressCallback, 0, "Initializing",
                "Starting standalone function implementation");

        // Get Oracle function metadata from state
        List<FunctionMetadata> oracleFunctions = stateService.getOracleFunctionMetadata();
        if (oracleFunctions == null || oracleFunctions.isEmpty()) {
            updateProgress(progressCallback, 100, "Complete",
                    "No Oracle functions found in state");
            return result;
        }

        // Filter to only standalone functions (exclude package members)
        List<FunctionMetadata> standaloneFunctions = oracleFunctions.stream()
                .filter(FunctionMetadata::isStandalone)
                .collect(Collectors.toList());

        if (standaloneFunctions.isEmpty()) {
            updateProgress(progressCallback, 100, "Complete",
                    "No standalone functions to implement (only package members found)");
            return result;
        }

        log.info("Found {} standalone functions to implement (out of {} total functions)",
                standaloneFunctions.size(), oracleFunctions.size());

        updateProgress(progressCallback, 10, "Building metadata indices",
                "Creating transformation indices from state");

        // Build transformation indices from state
        List<String> schemas = standaloneFunctions.stream()
                .map(FunctionMetadata::getSchema)
                .distinct()
                .toList();

        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

        updateProgress(progressCallback, 15, "Connecting to Oracle",
                "Establishing Oracle database connection for source extraction");

        // Open Oracle connection for source extraction
        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected to Oracle",
                    "Oracle connection established");

            updateProgress(progressCallback, 25, "Connecting to PostgreSQL",
                    "Establishing PostgreSQL database connection");

            try (Connection postgresConnection = postgresConnectionService.getConnection()) {
                updateProgress(progressCallback, 30, "Connected to both databases",
                        String.format("Processing %d standalone functions", standaloneFunctions.size()));

                int processed = 0;
                int totalFunctions = standaloneFunctions.size();

                for (FunctionMetadata function : standaloneFunctions) {
                    try {
                        updateProgress(progressCallback,
                                25 + (processed * 65 / totalFunctions),
                                "Processing function: " + function.getDisplayName(),
                                String.format("Function %d of %d", processed + 1, totalFunctions));

                        // Step 1: Extract Oracle source
                        log.info("Extracting Oracle source for: {}", function.getDisplayName());
                        String oracleSource = extractOracleFunctionSource(oracleConnection, function);

                        log.info("Successfully extracted Oracle source for: {} ({} characters)",
                                function.getDisplayName(), oracleSource.length());
                        log.debug("Oracle source for {}:\n{}", function.getDisplayName(), oracleSource);

                        // Step 2: Transform PL/SQL to PL/pgSQL
                        log.info("Transforming PL/SQL to PL/pgSQL for: {}", function.getDisplayName());
                        TransformationResult transformResult;

                        if (function.isFunction()) {
                            transformResult = transformationService.transformFunction(
                                    oracleSource, function.getSchema(), indices);
                        } else {
                            transformResult = transformationService.transformProcedure(
                                    oracleSource, function.getSchema(), indices);
                        }

                        if (!transformResult.isSuccess()) {
                            // Transformation failed - skip this function
                            String reason = "Transformation failed: " + transformResult.getErrorMessage();
                            result.addSkippedFunction(function);
                            log.warn("Skipped {}: {}", function.getDisplayName(), reason);
                        } else {
                            // Step 3: Execute CREATE OR REPLACE FUNCTION/PROCEDURE in PostgreSQL
                            String pgSql = transformResult.getPostgresSql();
                            log.info("Executing transformed SQL for: {}", function.getDisplayName());
                            log.debug("PostgreSQL SQL for {}:\n{}", function.getDisplayName(), pgSql);

                            executeInPostgres(postgresConnection, pgSql, function, result);
                        }

                    } catch (Exception e) {
                        log.error("Error processing function: " + function.getDisplayName(), e);
                        result.addError(function.getDisplayName(), e.getMessage(), null);
                    }

                    processed++;
                }

                updateProgress(progressCallback, 90, "Finalizing",
                        "Standalone function implementation complete");

                log.info("Standalone function implementation complete: {} implemented, {} skipped, {} errors",
                        result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());

                return result;
            }
        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Standalone function implementation failed: " + e.getMessage());
            log.error("Standalone function implementation failed", e);
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(StandaloneFunctionImplementationResult result) {
        return String.format("Standalone function implementation completed: %d implemented, %d skipped, %d errors",
                result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());
    }

    /**
     * Extracts Oracle function/procedure source code from ALL_SOURCE.
     *
     * Oracle stores PL/SQL source in ALL_SOURCE with one line per row.
     * This method assembles the complete source code by:
     * 1. Querying ALL_SOURCE for the function/procedure
     * 2. Ordering by LINE to maintain correct order
     * 3. Concatenating all lines into a single string
     *
     * @param oracleConn Oracle database connection
     * @param function Function metadata (contains schema and name)
     * @return Complete Oracle PL/SQL source code as a single string
     * @throws SQLException if source extraction fails
     */
    private String extractOracleFunctionSource(Connection oracleConn, FunctionMetadata function) throws SQLException {
        String schema = function.getSchema().toUpperCase();
        String name = function.getObjectName().toUpperCase();
        String type = function.isFunction() ? "FUNCTION" : "PROCEDURE";

        // Query ALL_SOURCE to get the complete PL/SQL source
        // ALL_SOURCE contains one row per line of code, ordered by LINE column
        String sql = """
            SELECT text
            FROM all_source
            WHERE owner = ?
              AND name = ?
              AND type = ?
            ORDER BY line
            """;

        StringBuilder source = new StringBuilder();

        try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, type);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString("text");
                    if (line != null) {
                        source.append(line);
                        // Note: Oracle ALL_SOURCE.TEXT does NOT include newlines
                        // We add them here to preserve line structure for debugging
                        if (!line.endsWith("\n")) {
                            source.append("\n");
                        }
                    }
                }
            }
        }

        String result = source.toString().trim();

        if (result.isEmpty()) {
            throw new SQLException(String.format(
                "No source found in ALL_SOURCE for %s %s.%s",
                type, schema, name
            ));
        }

        return result;
    }

    /**
     * Executes transformed PL/pgSQL in PostgreSQL.
     * Creates or replaces the function/procedure in the target database.
     *
     * @param postgresConn PostgreSQL database connection
     * @param pgSql Transformed CREATE OR REPLACE FUNCTION/PROCEDURE statement
     * @param function Function metadata
     * @param result Result tracker
     */
    private void executeInPostgres(Connection postgresConn, String pgSql,
                                   FunctionMetadata function,
                                   StandaloneFunctionImplementationResult result) {
        try (PreparedStatement ps = postgresConn.prepareStatement(pgSql)) {
            ps.execute();
            result.addImplementedFunction(function);
            log.info("Successfully implemented: {}", function.getDisplayName());
        } catch (SQLException e) {
            String errorMsg = String.format("Failed to execute in PostgreSQL: %s", e.getMessage());
            result.addError(function.getDisplayName(), errorMsg, pgSql);
            log.error("Failed to implement {}: {}", function.getDisplayName(), errorMsg, e);
        }
    }
}
