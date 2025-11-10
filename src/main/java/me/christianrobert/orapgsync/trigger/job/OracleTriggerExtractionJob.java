package me.christianrobert.orapgsync.trigger.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.trigger.service.OracleTriggerExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Oracle Trigger Extraction Job.
 *
 * This job extracts trigger metadata and DDL from Oracle database.
 *
 * The job:
 * 1. Queries Oracle system catalogs (ALL_TRIGGERS)
 * 2. Extracts trigger metadata (name, table, type, timing, event, level)
 * 3. Extracts trigger body (PL/SQL code)
 * 4. Extracts WHEN clauses if present
 * 5. Stores results in state for transformation
 */
@Dependent
public class OracleTriggerExtractionJob extends AbstractDatabaseExtractionJob<TriggerMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleTriggerExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "TRIGGER";
    }

    @Override
    public Class<TriggerMetadata> getResultType() {
        return TriggerMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TriggerMetadata> results) {
        stateService.setOracleTriggerMetadata(results);
    }

    @Override
    protected List<TriggerMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting Oracle trigger extraction");

        // Determine which schemas to process
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for trigger extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting trigger extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting trigger extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<TriggerMetadata> allTriggers = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                int progressPercentage = 10 + (processedSchemas * 80 / totalSchemas);

                updateProgress(progressCallback, progressPercentage,
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<TriggerMetadata> schemaTriggers =
                        OracleTriggerExtractor.extractAllTriggers(oracleConnection, List.of(schema));

                    allTriggers.addAll(schemaTriggers);

                    log.info("Extracted {} triggers from schema {}", schemaTriggers.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract triggers for schema: " + schema, e);
                    updateProgress(progressCallback, progressPercentage,
                            "Error in schema: " + schema,
                            "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            updateProgress(progressCallback, 90, "Finalizing",
                    String.format("Extracted %d triggers", allTriggers.size()));

            log.info("Trigger extraction completed: {} triggers extracted", allTriggers.size());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Extracted %d triggers from %d schemas",
                        allTriggers.size(), validSchemas.size()));

            return allTriggers;

        } catch (Exception e) {
            log.error("Trigger extraction failed", e);
            updateProgress(progressCallback, -1, "Failed",
                    "Trigger extraction failed: " + e.getMessage());
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

        return String.format("Extraction completed: %d triggers from %d schemas",
                results.size(), schemaSummary.size());
    }
}
