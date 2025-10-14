package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationVerificationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Verifies PostgreSQL view implementations (Phase 2).
 * Checks that views have been properly implemented with actual SQL (not stubs).
 *
 * Verification checks:
 * 1. View exists in PostgreSQL
 * 2. View is NOT a stub (does not contain "WHERE false" pattern)
 * 3. View can return data (executes without error)
 * 4. Optional: Row count comparison with Oracle views
 */
@Dependent
public class PostgresViewImplementationVerificationJob extends AbstractDatabaseExtractionJob<ViewImplementationVerificationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewImplementationVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "VIEW_IMPLEMENTATION_VERIFICATION";
    }

    @Override
    public Class<ViewImplementationVerificationResult> getResultType() {
        return ViewImplementationVerificationResult.class;
    }

    @Override
    protected void saveResultsToState(List<ViewImplementationVerificationResult> results) {
        // This is a verification job - results are not saved to state
        // They are returned directly to the user for review
        log.info("View implementation verification completed: {} result(s)", results.size());
    }

    @Override
    protected List<ViewImplementationVerificationResult> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting PostgreSQL view implementation verification");

        updateProgress(progressCallback, 0, "Initializing", "Starting view implementation verification");

        ViewImplementationVerificationResult result = new ViewImplementationVerificationResult();

        // Get PostgreSQL views from state
        List<ViewMetadata> postgresViews = stateService.getPostgresViewMetadata();

        if (postgresViews == null || postgresViews.isEmpty()) {
            log.warn("No PostgreSQL views found in state for verification");
            updateProgress(progressCallback, 100, "Completed", "No views to verify");
            return List.of(result);
        }

        log.info("Verifying {} PostgreSQL views", postgresViews.size());
        updateProgress(progressCallback, 10, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 15, "Connected", "Successfully connected to PostgreSQL database");

            int totalViews = postgresViews.size();
            int processedViews = 0;

            for (ViewMetadata view : postgresViews) {
                String qualifiedName = view.getSchema() + "." + view.getViewName();

                updateProgress(progressCallback,
                        15 + (processedViews * 70 / totalViews),
                        "Verifying view: " + qualifiedName,
                        String.format("View %d of %d", processedViews + 1, totalViews));

                try {
                    verifyViewImplementation(pgConnection, view, result);
                } catch (Exception e) {
                    log.error("Failed to verify view: " + qualifiedName, e);
                    result.addFailed(qualifiedName, "Verification failed: " + e.getMessage());
                }

                processedViews++;
            }

            updateProgress(progressCallback, 90, "Finalizing", "Generating verification report");

            log.info("View implementation verification completed: {} verified, {} failed, {} warnings",
                    result.getVerifiedCount(), result.getFailedCount(), result.getWarningCount());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Verified %d views: %d passed, %d failed, %d warnings",
                            result.getTotalProcessed(), result.getVerifiedCount(), result.getFailedCount(), result.getWarningCount()));

            return List.of(result);

        } catch (Exception e) {
            log.error("View implementation verification failed", e);
            updateProgress(progressCallback, -1, "Failed", "Verification failed: " + e.getMessage());
            throw e;
        }
    }

    private void verifyViewImplementation(Connection pgConnection, ViewMetadata view,
                                         ViewImplementationVerificationResult result) throws SQLException {
        String schema = view.getSchema();
        String viewName = view.getViewName();
        String qualifiedName = schema + "." + viewName;

        // Check if view exists
        if (!viewExists(pgConnection, schema, viewName)) {
            result.addFailed(qualifiedName, "View does not exist in PostgreSQL");
            return;
        }

        // Get view definition to check if it's still a stub
        String viewDef = getViewDefinition(pgConnection, schema, viewName);
        if (viewDef == null) {
            result.addFailed(qualifiedName, "Could not retrieve view definition");
            return;
        }

        // Check if view is a stub (contains WHERE false pattern)
        if (isStubView(viewDef)) {
            result.addFailed(qualifiedName, "View is still a stub (contains WHERE false pattern)");
            return;
        }

        // Try to execute the view and get row count
        try {
            long rowCount = getViewRowCount(pgConnection, schema, viewName);
            result.addVerified(qualifiedName, rowCount);

            // Optional: Add warning if view returns no data
            if (rowCount == 0) {
                result.addWarning(qualifiedName, "View returns 0 rows (may be expected)");
            }

        } catch (SQLException e) {
            result.addFailed(qualifiedName, "View execution failed: " + e.getMessage());
        }
    }

    private boolean viewExists(Connection pgConnection, String schema, String viewName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.views WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement ps = pgConnection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private String getViewDefinition(Connection pgConnection, String schema, String viewName) throws SQLException {
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

    private boolean isStubView(String viewDef) {
        if (viewDef == null) {
            return false;
        }
        String normalized = viewDef.toUpperCase();
        return normalized.contains("WHERE FALSE") || normalized.contains("WHERE false");
    }

    private long getViewRowCount(Connection pgConnection, String schema, String viewName) throws SQLException {
        String sql = String.format("SELECT COUNT(*) FROM %s.%s",
                pgConnection.createStatement().enquoteIdentifier(schema, true),
                pgConnection.createStatement().enquoteIdentifier(viewName, true));

        try (PreparedStatement ps = pgConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    @Override
    protected String generateSummaryMessage(List<ViewImplementationVerificationResult> results) {
        if (results == null || results.isEmpty()) {
            return "View implementation verification completed: No views verified";
        }

        ViewImplementationVerificationResult result = results.get(0);
        return String.format("View implementation verification completed: %d verified, %d failed, %d warnings",
                result.getVerifiedCount(), result.getFailedCount(), result.getWarningCount());
    }
}
