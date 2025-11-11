package me.christianrobert.orapgsync.trigger.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.trigger.service.PostgresTriggerExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * PostgreSQL Trigger Verification Job.
 *
 * This job extracts trigger metadata from PostgreSQL database
 * to verify what triggers have been successfully created.
 *
 * The job:
 * 1. Queries PostgreSQL system catalogs (pg_trigger, pg_proc, etc.)
 * 2. Extracts all trigger metadata
 * 3. Filters out system schemas
 * 4. Returns the list for frontend display and comparison
 *
 * Note: This job is INDEPENDENT of StateService trigger metadata.
 * It directly queries PostgreSQL to verify what actually exists in the database.
 */
@Dependent
public class PostgresTriggerVerificationJob extends AbstractDatabaseExtractionJob<TriggerMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTriggerVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    protected List<String> getAvailableSchemas() {
        // For PostgreSQL extraction, query the database directly instead of relying on state
        log.debug("Querying PostgreSQL database for available schemas");
        try (Connection connection = postgresConnectionService.getConnection()) {
            return fetchSchemasFromPostgres(connection);
        } catch (Exception e) {
            log.error("Failed to fetch schemas from PostgreSQL, falling back to state", e);
            return stateService.getPostgresSchemaNames();
        }
    }

    private List<String> fetchSchemasFromPostgres(Connection connection) throws Exception {
        List<String> schemas = new ArrayList<>();
        String sql = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            ORDER BY schema_name
            """;

        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                schemas.add(rs.getString("schema_name"));
            }
        }

        log.debug("Found {} schemas in PostgreSQL database", schemas.size());
        return schemas;
    }

    @Override
    public String getExtractionType() {
        return "TRIGGER_VERIFICATION";
    }

    @Override
    public Class<TriggerMetadata> getResultType() {
        return TriggerMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TriggerMetadata> results) {
        stateService.setPostgresTriggerMetadata(results);
    }

    @Override
    protected List<TriggerMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting PostgreSQL trigger verification");

        // Determine which schemas to process
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for trigger verification based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting trigger verification for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting trigger verification for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        List<TriggerMetadata> allTriggers = new ArrayList<>();

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to PostgreSQL database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                int progressPercentage = 10 + (processedSchemas * 80 / totalSchemas);

                updateProgress(progressCallback, progressPercentage,
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<TriggerMetadata> schemaTriggers =
                        PostgresTriggerExtractor.extractAllTriggers(pgConnection, List.of(schema));

                    allTriggers.addAll(schemaTriggers);

                    log.info("Verified {} triggers from schema {}", schemaTriggers.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to verify triggers for schema: " + schema, e);
                    updateProgress(progressCallback, progressPercentage,
                            "Error in schema: " + schema,
                            "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            updateProgress(progressCallback, 90, "Finalizing",
                    String.format("Verified %d triggers", allTriggers.size()));

            log.info("Trigger verification completed: {} triggers verified", allTriggers.size());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Verified %d triggers from %d schemas",
                        allTriggers.size(), validSchemas.size()));

            return allTriggers;

        } catch (Exception e) {
            log.error("Trigger verification failed", e);
            updateProgress(progressCallback, -1, "Failed",
                    "Trigger verification failed: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(List<TriggerMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();

        for (TriggerMetadata trigger : results) {
            String schema = trigger.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);
        }

        return String.format("Verification completed: %d triggers from %d schemas",
                results.size(), schemaSummary.size());
    }
}
