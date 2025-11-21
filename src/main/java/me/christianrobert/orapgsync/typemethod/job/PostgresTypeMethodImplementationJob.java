package me.christianrobert.orapgsync.typemethod.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodImplementationResult;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodMetadata;
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
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

/**
 * PostgreSQL Type Method Implementation Job.
 *
 * This job implements type member methods by transforming Oracle PL/SQL to PostgreSQL PL/pgSQL
 * and replacing stub implementations.
 *
 * The job:
 * 1. Retrieves Oracle type method metadata from state
 * 2. For each type method:
 *    a. Extract Oracle PL/SQL source from StateService (pre-extracted via segmentation)
 *    b. Transform PL/SQL → PL/pgSQL using ANTLR
 *    c. Execute CREATE OR REPLACE FUNCTION in PostgreSQL
 * 3. Returns results tracking success/skipped/errors
 *
 * Uses type method segmentation optimization:
 * - Sources pre-extracted during extraction job (TypeMethodBoundaryScanner)
 * - Stored in StateService for instant retrieval
 * - No ANTLR parsing during extraction, only during transformation
 * - Memory efficient (800x reduction vs full parse)
 */
@Dependent
public class PostgresTypeMethodImplementationJob extends AbstractDatabaseWriteJob<TypeMethodImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTypeMethodImplementationJob.class);

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
        return "TYPE_METHOD_IMPLEMENTATION";
    }

    @Override
    public Class<TypeMethodImplementationResult> getResultType() {
        return TypeMethodImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(TypeMethodImplementationResult result) {
        stateService.setTypeMethodImplementationResult(result);
    }

    @Override
    protected TypeMethodImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        TypeMethodImplementationResult result = new TypeMethodImplementationResult();

        updateProgress(progressCallback, 0, "Initializing",
                "Starting type method implementation");

        // Get Oracle type method metadata from state
        List<TypeMethodMetadata> oracleTypeMethods = stateService.getOracleTypeMethodMetadata();
        if (oracleTypeMethods == null || oracleTypeMethods.isEmpty()) {
            updateProgress(progressCallback, 100, "Complete",
                    "No Oracle type methods found in state");
            return result;
        }

        // Count member vs static methods
        long memberCount = oracleTypeMethods.stream().filter(TypeMethodMetadata::isMemberMethod).count();
        long staticCount = oracleTypeMethods.size() - memberCount;

        log.info("Found {} type methods to implement: {} member, {} static",
                oracleTypeMethods.size(), memberCount, staticCount);

        updateProgress(progressCallback, 10, "Building metadata indices",
                "Creating transformation indices from state");

        // Build transformation indices from state
        List<String> schemas = oracleTypeMethods.stream()
                .map(TypeMethodMetadata::getSchema)
                .distinct()
                .toList();

        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

        updateProgress(progressCallback, 15, "Connecting to PostgreSQL",
                "Establishing PostgreSQL database connection");

        // Open PostgreSQL connection for execution
        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected to PostgreSQL",
                    String.format("Processing %d type methods (%d member, %d static)",
                                  oracleTypeMethods.size(), memberCount, staticCount));

            int processed = 0;
            int totalMethods = oracleTypeMethods.size();

            for (TypeMethodMetadata method : oracleTypeMethods) {
                try {
                    updateProgress(progressCallback,
                            20 + (processed * 70 / totalMethods),
                            "Processing type method: " + method.getDisplayName(),
                            String.format("Method %d of %d", processed + 1, totalMethods));

                    // Step 1: Extract Oracle source from StateService (segmentation optimization)
                    log.info("Extracting Oracle source for: {}", method.getDisplayName());
                    String oracleSource = extractOracleTypeMethodSource(method);

                    log.info("Successfully extracted Oracle source for: {} ({} characters)",
                            method.getDisplayName(), oracleSource.length());
                    log.debug("Oracle source for {}:\n{}", method.getDisplayName(), oracleSource);

                    // Step 2: Transform PL/SQL to PL/pgSQL
                    log.info("Transforming PL/SQL to PL/pgSQL for: {}", method.getDisplayName());
                    TransformationResult transformResult;

                    if (method.isFunction()) {
                        transformResult = transformationService.transformFunction(
                                oracleSource,
                                method.getSchema(),
                                indices);
                    } else {
                        transformResult = transformationService.transformProcedure(
                                oracleSource,
                                method.getSchema(),
                                indices);
                    }

                    if (!transformResult.isSuccess()) {
                        // Transformation failed - skip this method
                        String reason = "Transformation failed: " + transformResult.getErrorMessage();
                        result.addSkippedTypeMethod(method);
                        log.warn("Skipped {}: {}", method.getDisplayName(), reason);
                    } else {
                        // Step 3: Execute CREATE OR REPLACE FUNCTION in PostgreSQL
                        String pgSql = transformResult.getPostgresSql();
                        log.info("Executing transformed SQL for: {}", method.getDisplayName());
                        log.debug("PostgreSQL SQL for {}:\n{}", method.getDisplayName(), pgSql);

                        executeInPostgres(postgresConnection, pgSql, method, result);
                    }

                } catch (Exception e) {
                    log.error("Error processing type method: " + method.getDisplayName(), e);
                    result.addError(method.getDisplayName(), e.getMessage(), null);
                }

                processed++;
            }

            updateProgress(progressCallback, 90, "Finalizing",
                    "Type method implementation complete");

            log.info("Type method implementation complete: {} implemented, {} skipped, {} errors",
                    result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());

            // NOTE: Type method sources remain in StateService for re-runs
            // They will be cleared only when user manually resets state
            // (Memory optimization must not compromise re-run capability)

            return result;
        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Type method implementation failed: " + e.getMessage());
            log.error("Type method implementation failed", e);
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(TypeMethodImplementationResult result) {
        return String.format("Type method implementation completed: %d implemented, %d skipped, %d errors",
                result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());
    }

    /**
     * Extracts Oracle type method source code from StateService.
     * Sources were pre-extracted during the extraction job via TypeMethodBoundaryScanner.
     *
     * Type Method Segmentation Optimization:
     * - No querying of ALL_SOURCE needed
     * - No ANTLR parsing needed
     * - Direct retrieval from StateService (instant, ~O(1) lookup)
     * - Sources stored during OracleTypeMethodExtractionJob
     *
     * @param method Type method metadata
     * @return Oracle PL/SQL source code for the type method
     * @throws SQLException if type method source not found in StateService
     */
    private String extractOracleTypeMethodSource(TypeMethodMetadata method) throws SQLException {
        // Get method source from StateService (stored during extraction job)
        String source = stateService.getTypeMethodSource(
            method.getSchema(),
            method.getTypeName(),
            method.getMethodName()
        );

        if (source == null) {
            throw new SQLException(String.format(
                "Type method %s.%s.%s not found in StateService - extraction job may not have run",
                method.getSchema(),
                method.getTypeName(),
                method.getMethodName()
            ));
        }

        log.debug("Retrieved type method source from StateService: {}.{}.{} ({} chars)",
                 method.getSchema(), method.getTypeName(), method.getMethodName(), source.length());

        return source;
    }

    /**
     * Executes transformed PL/pgSQL in PostgreSQL.
     * Creates or replaces the type method function in the target database.
     *
     * @param postgresConn PostgreSQL database connection
     * @param pgSql Transformed CREATE OR REPLACE FUNCTION statement
     * @param method Type method metadata
     * @param result Result tracker
     */
    private void executeInPostgres(Connection postgresConn, String pgSql,
                                   TypeMethodMetadata method,
                                   TypeMethodImplementationResult result) {
        try (PreparedStatement ps = postgresConn.prepareStatement(pgSql)) {
            ps.execute();
            result.addImplementedTypeMethod(method);
            log.info("Successfully implemented: {}", method.getDisplayName());
        } catch (SQLException e) {
            String errorMsg = String.format("Failed to execute in PostgreSQL: %s", e.getMessage());
            result.addError(method.getDisplayName(), errorMsg, pgSql);
            log.error("Failed to implement {}: {}", method.getDisplayName(), errorMsg, e);
        }
    }
}
