package me.christianrobert.orapgsync.schema.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Dependent
public class OracleSchemaExtractionJob extends AbstractDatabaseExtractionJob<String> {

    private static final Logger log = LoggerFactory.getLogger(OracleSchemaExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "SCHEMA";
    }

    @Override
    public Class<String> getResultType() {
        return String.class;
    }

    @Override
    protected void saveResultsToState(List<String> results) {
        stateService.setOracleSchemaNames(results);
        log.debug("Saved {} Oracle schemas to global state", results.size());
    }

    @Override
    protected List<String> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting Oracle schema extraction");

        if (!oracleConnectionService.isConfigured()) {
            updateProgress(progressCallback, -1, "Failed", "Oracle connection not configured");
            throw new IllegalStateException("Oracle connection not configured");
        }

        updateProgress(progressCallback, 10, "Connecting to Oracle", "Establishing database connection");

        List<String> schemas = new ArrayList<>();

        try (Connection connection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected", "Successfully connected to Oracle database");

            String sql = "SELECT username FROM all_users ORDER BY username";

            updateProgress(progressCallback, 30, "Executing query", "Fetching schemas from Oracle");

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                int count = 0;
                while (rs.next()) {
                    String username = rs.getString("username");
                    if (!UserExcluder.is2BeExclueded(username)) {
                        schemas.add(username.toLowerCase());
                        count++;

                        if (count % 10 == 0) {
                            int progress = 30 + (count * 50 / Math.max(1, count + 10));
                            updateProgress(progressCallback, progress, "Processing schemas",
                                String.format("Found %d schemas so far", count));
                        }
                    } else {
                        log.debug("Excluding Oracle schema: {}", username);
                    }
                }

                updateProgress(progressCallback, 80, "Processing completed",
                    String.format("Found %d Oracle schemas", schemas.size()));
            }
        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Schema extraction failed: " + e.getMessage());
            throw e;
        }

        return schemas;
    }

    @Override
    protected String generateSummaryMessage(List<String> results) {
        return String.format("Extraction completed: %d Oracle schemas found", results.size());
    }

    @Override
    protected List<String> getAvailableSchemas() {
        // For schema extraction, we don't use the determineSchemasToProcess logic
        // since we're discovering the schemas themselves
        return new ArrayList<>();
    }

    @Override
    protected List<String> determineSchemasToProcess(Consumer<JobProgress> progressCallback) {
        // Schema extraction doesn't use this - it discovers all schemas
        return new ArrayList<>();
    }
}
