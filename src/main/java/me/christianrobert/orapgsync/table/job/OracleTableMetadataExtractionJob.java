package me.christianrobert.orapgsync.table.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import me.christianrobert.orapgsync.table.service.OracleTableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class OracleTableMetadataExtractionJob extends AbstractDatabaseExtractionJob<TableMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleTableMetadataExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "TABLE_METADATA";
    }

    @Override
    public Class<TableMetadata> getResultType() {
        return TableMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TableMetadata> results) {
        stateService.setOracleTableMetadata(results);
    }

    @Override
    protected List<TableMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process", "No schemas available for table extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing", "Starting table metadata extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting table metadata extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<TableMetadata> allTableMetadata = new ArrayList<>();

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
                    List<TableMetadata> schemaTableMetadata = extractTablesForSchema(
                        oracleConnection, schema, progressCallback, processedSchemas, totalSchemas);

                    allTableMetadata.addAll(schemaTableMetadata);

                    log.info("Extracted {} tables from schema {}", schemaTableMetadata.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract tables for schema: " + schema, e);
                    updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Error in schema: " + schema,
                        "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            return allTableMetadata;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Table metadata extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<TableMetadata> extractTablesForSchema(Connection oracleConnection, String schema,
                                                     Consumer<JobProgress> progressCallback,
                                                     int currentSchemaIndex, int totalSchemas) throws Exception {

        List<String> singleSchemaList = List.of(schema);
        return OracleTableExtractor.extractAllTables(oracleConnection, singleSchemaList);
    }

    @Override
    protected String generateSummaryMessage(List<TableMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        for (TableMetadata table : results) {
            String schema = table.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);
        }

        return String.format("Extraction completed: %d tables from %d schemas",
                           results.size(), schemaSummary.size());
    }
}