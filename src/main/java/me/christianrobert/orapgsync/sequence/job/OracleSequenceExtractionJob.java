package me.christianrobert.orapgsync.sequence.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.sequence.service.OracleSequenceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class OracleSequenceExtractionJob extends AbstractDatabaseExtractionJob<SequenceMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleSequenceExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "SEQUENCE";
    }

    @Override
    public Class<SequenceMetadata> getResultType() {
        return SequenceMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<SequenceMetadata> results) {
        stateService.setOracleSequenceMetadata(results);
    }

    @Override
    protected List<SequenceMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for sequence extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting sequence extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting Oracle sequence extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<SequenceMetadata> allSequenceMetadata = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<SequenceMetadata> schemaSequenceMetadata =
                            OracleSequenceExtractor.extractAllSequences(oracleConnection, List.of(schema));

                    allSequenceMetadata.addAll(schemaSequenceMetadata);

                    log.info("Extracted {} sequences from schema {}", schemaSequenceMetadata.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract sequences for schema: " + schema, e);
                    updateProgress(progressCallback,
                            10 + (processedSchemas * 80 / totalSchemas),
                            "Error in schema: " + schema,
                            "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            return allSequenceMetadata;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Sequence extraction failed: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(List<SequenceMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        for (SequenceMetadata sequence : results) {
            String schema = sequence.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);
        }

        return String.format("Extraction completed: %d sequences from %d schemas",
                results.size(), schemaSummary.size());
    }
}
