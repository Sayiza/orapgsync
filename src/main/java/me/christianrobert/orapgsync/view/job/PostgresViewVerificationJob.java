package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.view.ViewVerificationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewVerificationResult.ViewInfo;
import me.christianrobert.orapgsync.core.job.model.view.ViewVerificationResult.ViewStatus;
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
 * Unified PostgreSQL view verification job.
 *
 * Verifies all PostgreSQL views (both stubs and implementations) by:
 * 1. Querying PostgreSQL directly (no state dependency)
 * 2. Extracting view DDL using pg_get_viewdef()
 * 3. Auto-detecting status (STUB vs IMPLEMENTED) from DDL content
 * 4. Grouping results by schema for easy navigation
 *
 * This job does NOT execute row counts for performance reasons.
 * The DDL is provided for manual inspection in the frontend.
 */
@Dependent
public class PostgresViewVerificationJob extends AbstractDatabaseExtractionJob<ViewVerificationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "VIEW_VERIFICATION";
    }

    @Override
    public Class<ViewVerificationResult> getResultType() {
        return ViewVerificationResult.class;
    }

    @Override
    protected void saveResultsToState(List<ViewVerificationResult> results) {
        // This is a verification job - results are not saved to state
        // They are returned directly to the user for review
        if (!results.isEmpty()) {
            ViewVerificationResult result = results.get(0);
            log.info("View verification completed: {} total views ({} implemented, {} stubs, {} errors) across {} schemas",
                    result.getTotalViews(), result.getImplementedCount(), result.getStubCount(),
                    result.getErrorCount(), result.getSchemas().size());
        }
    }

    @Override
    protected List<ViewVerificationResult> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting unified PostgreSQL view verification");

        updateProgress(progressCallback, 0, "Initializing", "Starting view verification");

        ViewVerificationResult result = new ViewVerificationResult();

        updateProgress(progressCallback, 10, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 15, "Connected", "Successfully connected to PostgreSQL database");

            // Query all views from PostgreSQL directly (no state dependency)
            List<ViewEntry> allViews = queryAllViews(pgConnection);

            if (allViews.isEmpty()) {
                log.warn("No views found in PostgreSQL database");
                updateProgress(progressCallback, 100, "Completed", "No views to verify");
                return List.of(result);
            }

            log.info("Found {} views across all schemas, extracting DDL", allViews.size());

            int totalViews = allViews.size();
            int processedViews = 0;

            for (ViewEntry entry : allViews) {
                String schema = entry.schema;
                String viewName = entry.viewName;
                String qualifiedName = schema + "." + viewName;

                updateProgress(progressCallback,
                        15 + (processedViews * 70 / totalViews),
                        "Processing view: " + qualifiedName,
                        String.format("View %d of %d", processedViews + 1, totalViews));

                ViewInfo viewInfo = new ViewInfo(viewName);

                try {
                    // Extract view DDL
                    String ddl = extractViewDdl(pgConnection, schema, viewName);

                    if (ddl == null || ddl.trim().isEmpty()) {
                        viewInfo.setStatus(ViewStatus.ERROR);
                        viewInfo.setErrorMessage("Could not retrieve view DDL");
                        log.warn("Could not retrieve DDL for view: {}", qualifiedName);
                    } else {
                        // Auto-detect status from DDL content
                        ViewStatus status = detectStatusFromDdl(ddl);
                        viewInfo.setStatus(status);
                        viewInfo.setViewDdl(ddl);

                        log.debug("View {}: status = {}", qualifiedName, status);
                    }

                } catch (Exception e) {
                    log.error("Failed to process view: " + qualifiedName, e);
                    viewInfo.setStatus(ViewStatus.ERROR);
                    viewInfo.setErrorMessage("Failed to extract DDL: " + e.getMessage());
                }

                result.addView(schema, viewInfo);
                processedViews++;
            }

            updateProgress(progressCallback, 90, "Finalizing", "Generating verification report");

            log.info("View verification completed: {} total views ({} implemented, {} stubs, {} errors) across {} schemas",
                    result.getTotalViews(), result.getImplementedCount(), result.getStubCount(),
                    result.getErrorCount(), result.getSchemas().size());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Verified %d views: %d implemented, %d stubs, %d errors",
                            result.getTotalViews(), result.getImplementedCount(),
                            result.getStubCount(), result.getErrorCount()));

            return List.of(result);

        } catch (Exception e) {
            log.error("View verification failed", e);
            updateProgress(progressCallback, -1, "Failed", "Verification failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Queries all views from PostgreSQL database.
     * Returns schema and view name pairs.
     */
    private List<ViewEntry> queryAllViews(Connection pgConnection) throws SQLException {
        List<ViewEntry> views = new ArrayList<>();

        String sql = """
            SELECT table_schema, table_name
            FROM information_schema.views
            WHERE table_schema NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
              AND table_schema NOT LIKE 'pg_%'
            ORDER BY table_schema, table_name
            """;

        try (PreparedStatement ps = pgConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("table_schema");
                String viewName = rs.getString("table_name");
                views.add(new ViewEntry(schema, viewName));
            }
        }

        log.info("Found {} views in PostgreSQL database", views.size());
        return views;
    }

    /**
     * Extracts view DDL using pg_get_viewdef().
     * The second parameter (true) enables pretty-printing for readability.
     */
    private String extractViewDdl(Connection pgConnection, String schema, String viewName) throws SQLException {
        // Use pg_get_viewdef with pretty-printing enabled
        String sql = "SELECT pg_get_viewdef(quote_ident(?) || '.' || quote_ident(?), true) AS viewdef";

        try (PreparedStatement ps = pgConnection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, viewName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("viewdef");
                }
            }
        }

        return null;
    }

    /**
     * Auto-detects view status from DDL content.
     *
     * STUB: Contains "WHERE false" or "WHERE FALSE" pattern (placeholder)
     * IMPLEMENTED: Full view implementation
     */
    private ViewStatus detectStatusFromDdl(String ddl) {
        if (ddl == null || ddl.trim().isEmpty()) {
            return ViewStatus.ERROR;
        }

        // Check for stub pattern: "WHERE false" or "WHERE FALSE"
        String normalized = ddl.toUpperCase();
        if (normalized.contains("WHERE FALSE") || normalized.contains("WHERE false")) {
            return ViewStatus.STUB;
        }

        return ViewStatus.IMPLEMENTED;
    }

    @Override
    protected String generateSummaryMessage(List<ViewVerificationResult> results) {
        if (results == null || results.isEmpty()) {
            return "View verification completed: No views verified";
        }

        ViewVerificationResult result = results.get(0);
        return String.format("View verification completed: %d total (%d implemented, %d stubs, %d errors)",
                result.getTotalViews(), result.getImplementedCount(),
                result.getStubCount(), result.getErrorCount());
    }

    /**
     * Simple holder for schema + view name pairs.
     */
    private static class ViewEntry {
        final String schema;
        final String viewName;

        ViewEntry(String schema, String viewName) {
            this.schema = schema;
            this.viewName = viewName;
        }
    }
}
