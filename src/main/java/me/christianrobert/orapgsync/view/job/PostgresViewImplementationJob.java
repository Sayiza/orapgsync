package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.ViewTransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Creates PostgreSQL view implementations (Phase 2) by replacing stubs with actual SQL.
 * This job transforms Oracle view SQL to PostgreSQL and replaces stub views.
 *
 * CRITICAL: Uses CREATE OR REPLACE VIEW to preserve dependencies!
 * The two-phase architecture depends on this:
 * - Phase 1 (stubs): Functions/procedures are created with references to stub views
 * - Phase 2 (implementation): Replace stubs WITHOUT dropping dependent objects
 *
 * Implementation:
 * 1. Reads Oracle view SQL from ViewMetadata.sqlDefinition
 * 2. Transforms SQL using ANTLR parser (Oracle → PostgreSQL)
 * 3. Replaces stub views using CREATE OR REPLACE VIEW
 * 4. Preserves all dependencies (functions, procedures, other views)
 * 5. Tracks success/failures in ViewImplementationResult
 */
@Dependent
public class PostgresViewImplementationJob extends AbstractDatabaseWriteJob<ViewImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewImplementationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private ViewTransformationService transformationService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "VIEW_IMPLEMENTATION";
    }

    @Override
    public Class<ViewImplementationResult> getResultType() {
        return ViewImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(ViewImplementationResult result) {
        stateService.setViewImplementationResult(result);
    }

    @Override
    protected ViewImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting PostgreSQL view implementation with SQL transformation");

        updateProgress(progressCallback, 0, "Initializing",
                "Starting view implementation process");

        ViewImplementationResult result = new ViewImplementationResult();

        // Get Oracle views from state
        List<ViewMetadata> oracleViews = stateService.getOracleViewMetadata();

        if (oracleViews == null || oracleViews.isEmpty()) {
            log.warn("No Oracle views found in state for implementation");
            updateProgress(progressCallback, 100, "No views to process",
                    "No Oracle views found in state. Please extract Oracle views first.");
            return result;
        }

        // Filter valid views (exclude system schemas and views without SQL)
        List<ViewMetadata> validViews = filterValidViews(oracleViews);

        if (validViews.isEmpty()) {
            log.warn("No valid Oracle views found for implementation");
            updateProgress(progressCallback, 100, "No valid views",
                    "No valid Oracle views with SQL definitions found");
            return result;
        }

        log.info("Found {} valid Oracle views to implement", validViews.size());
        updateProgress(progressCallback, 10, "Building metadata indices",
                String.format("Processing %d views", validViews.size()));

        // Build transformation indices once at the start
        List<String> schemas = stateService.getOracleSchemaNames();
        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

        updateProgress(progressCallback, 15, "Metadata indices built",
                "Ready to transform view SQL");

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL");

            int totalViews = validViews.size();
            int processedViews = 0;

            for (ViewMetadata view : validViews) {
                String qualifiedName = view.getSchema() + "." + view.getViewName();
                int progressPercentage = 25 + (processedViews * 70 / totalViews);

                updateProgress(progressCallback, progressPercentage,
                        "Implementing view: " + qualifiedName,
                        String.format("View %d of %d", processedViews + 1, totalViews));

                try {
                    implementView(pgConnection, view, indices, result);
                } catch (Exception e) {
                    log.error("Failed to implement view: " + qualifiedName, e);
                    result.addError(qualifiedName, "Implementation failed: " + e.getMessage(), null);
                }

                processedViews++;
            }

            updateProgress(progressCallback, 95, "Finalizing",
                    "View implementation completed");

            log.info("View implementation completed: {} implemented, {} skipped, {} errors",
                    result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Implemented %d views: %d succeeded, %d skipped, %d errors",
                            result.getTotalProcessed(), result.getImplementedCount(),
                            result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            log.error("View implementation failed", e);
            updateProgress(progressCallback, -1, "Failed",
                    "View implementation failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Implements a single view by transforming Oracle SQL to PostgreSQL.
     * Catches and logs SQL errors to prevent job crashes - individual view failures
     * should not stop processing of other views.
     */
    private void implementView(Connection pgConnection, ViewMetadata view,
                               TransformationIndices indices,
                               ViewImplementationResult result) {

        String schema = view.getSchema();
        String viewName = view.getViewName();
        String qualifiedName = schema + "." + viewName;
        String oracleSql = view.getSqlDefinition();

        // Check if Oracle SQL is available
        if (oracleSql == null || oracleSql.trim().isEmpty()) {
            log.warn("No SQL definition for view: {}", qualifiedName);
            result.addSkippedView(qualifiedName);
            return;
        }

        log.debug("Transforming view: {}", qualifiedName);
        log.trace("Oracle SQL: {}", oracleSql);

        // Transform Oracle SQL to PostgreSQL
        TransformationResult transformationResult = transformationService.transformViewSql(
                oracleSql, schema, indices);

        if (transformationResult.isFailure()) {
            log.error("Transformation failed for view {}: {}", qualifiedName,
                    transformationResult.getErrorMessage());
            result.addError(qualifiedName, transformationResult.getErrorMessage(), oracleSql);
            return;
        }

        String postgresSql = transformationResult.getPostgresSql();
        log.debug("Transformed SQL for {}: {}", qualifiedName, postgresSql);

        // Replace stub view with actual implementation
        // IMPORTANT: Use CREATE OR REPLACE to preserve dependencies (functions/procedures)
        // This is the whole point of the two-phase stub architecture!
        try {
            replaceView(pgConnection, schema, viewName, postgresSql);
            result.addImplementedView(qualifiedName);
            log.info("Successfully implemented view: {}", qualifiedName);
        } catch (SQLException e) {
            // Log SQL error but continue processing other views
            log.error("SQL error creating view {}: {}", qualifiedName, e.getMessage());
            log.debug("Failed SQL: CREATE OR REPLACE VIEW {}.{} AS {}",
                    schema.toLowerCase(), viewName.toLowerCase(), postgresSql);

            // Add error with the transformed SQL (shows what we tried to execute)
            String errorMsg = "SQL execution failed: " + e.getMessage();
            result.addError(qualifiedName, errorMsg, postgresSql);
        }
    }

    /**
     * Replaces an existing view with new SQL definition.
     * Uses CREATE OR REPLACE VIEW to preserve dependencies on the view.
     *
     * This is critical for the two-phase architecture:
     * - Phase 1: Stubs allow functions/procedures to be created with references to views
     * - Phase 2: Replace stubs WITHOUT breaking those dependencies
     */
    private void replaceView(Connection pgConnection, String schema, String viewName,
                            String selectSql) throws SQLException {
        String createOrReplaceSql = String.format("CREATE OR REPLACE VIEW %s.%s AS %s",
                schema.toLowerCase(), viewName.toLowerCase(), selectSql);

        log.debug("Replacing view with SQL: {}", createOrReplaceSql);

        try (PreparedStatement ps = pgConnection.prepareStatement(createOrReplaceSql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Filters views to only include valid ones (with SQL definitions and in valid schemas).
     */
    private List<ViewMetadata> filterValidViews(List<ViewMetadata> views) {
        List<ViewMetadata> validViews = new ArrayList<>();

        for (ViewMetadata view : views) {
            // Check if schema is valid
            if (filterValidSchemas(List.of(view.getSchema())).isEmpty()) {
                log.debug("Skipping view {} - schema excluded", view.getSchema() + "." + view.getViewName());
                continue;
            }

            // Check if SQL definition exists
            if (view.getSqlDefinition() == null || view.getSqlDefinition().trim().isEmpty()) {
                log.debug("Skipping view {} - no SQL definition", view.getSchema() + "." + view.getViewName());
                continue;
            }

            validViews.add(view);
        }

        return validViews;
    }

    @Override
    protected String generateSummaryMessage(ViewImplementationResult result) {
        if (result == null) {
            return "View implementation completed: No views processed";
        }

        return String.format("View implementation completed: %d implemented, %d skipped, %d errors",
                result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());
    }
}
