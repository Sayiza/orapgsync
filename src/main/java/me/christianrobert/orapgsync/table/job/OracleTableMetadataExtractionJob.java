package me.christianrobert.orapgsync.table.job;

import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import me.christianrobert.orapgsync.table.service.TableExtractor;
import me.christianrobert.orapgsync.table.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class OracleTableMetadataExtractionJob implements Job<List<TableMetadata>> {

    private static final Logger log = LoggerFactory.getLogger(OracleTableMetadataExtractionJob.class);

    private final String jobId;

    private StateService stateService;
    private OracleConnectionService oracleConnectionService;
    private ConfigService configService;

    public OracleTableMetadataExtractionJob() {
        this.jobId = "oracle-table-metadata-extraction-" + UUID.randomUUID().toString();
    }

    public void setStateService(StateService stateService) {
        this.stateService = stateService;
    }

    public void setOracleConnectionService(OracleConnectionService oracleConnectionService) {
        this.oracleConnectionService = oracleConnectionService;
    }

    public void setConfigService(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public String getJobType() {
        return "ORACLE_TABLE_METADATA_EXTRACTION";
    }

    @Override
    public String getDescription() {
        return "Extract table metadata from Oracle database and store in global state";
    }

    @Override
    public CompletableFuture<List<TableMetadata>> execute(Consumer<JobProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performExtraction(progressCallback);
            } catch (Exception e) {
                log.error("Table metadata extraction failed", e);
                throw new RuntimeException("Table metadata extraction failed: " + e.getMessage(), e);
            }
        });
    }

    private List<TableMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process", "No schemas available for table extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing", "Starting table metadata extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = new ArrayList<>();
        for (String schema : schemasToProcess) {
            if (!UserExcluder.is2BeExclueded(schema)) {
                validSchemas.add(schema);
            }
        }

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

            updateProgress(progressCallback, 90, "Storing results", "Saving table metadata to global state");

            stateService.updateOracleTableMetadata(allTableMetadata);

            updateProgress(progressCallback, 95, "Preparing summary", "Generating extraction summary");

            Map<String, Integer> schemaSummary = generateSchemaSummary(allTableMetadata);

            String summaryMessage = String.format(
                "Extraction completed: %d tables from %d schemas",
                allTableMetadata.size(),
                schemaSummary.size());

            updateProgress(progressCallback, 100, "Completed", summaryMessage);

            log.info("Table metadata extraction completed successfully: {} tables from {} schemas",
                    allTableMetadata.size(), schemaSummary.size());

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
        return TableExtractor.extractAllTables(oracleConnection, singleSchemaList);
    }

    private Map<String, Integer> generateSchemaSummary(List<TableMetadata> tableMetadata) {
        Map<String, Integer> summary = new HashMap<>();

        for (TableMetadata table : tableMetadata) {
            String schema = table.getSchema();
            summary.put(schema, summary.getOrDefault(schema, 0) + 1);
        }

        return summary;
    }

    private List<String> determineSchemasToProcess(Consumer<JobProgress> progressCallback) {
        updateProgress(progressCallback, 0, "Checking configuration", "Determining schemas to process based on configuration settings");

        // Check configuration settings
        boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
        String testSchema = configService.getConfigValueAsString("do.only-test-schema");

        if (doAllSchemas) {
            updateProgress(progressCallback, 0, "Using all schemas", "Configuration set to process all available schemas");
            log.info("Processing all schemas based on configuration (do.all-schemas=true)");
            return stateService.getOracleSchemaNames();
        } else {
            if (testSchema == null || testSchema.trim().isEmpty()) {
                updateProgress(progressCallback, 100, "Configuration error", "Test schema not configured but do.all-schemas is false");
                log.error("Test schema not configured but do.all-schemas is false");
                throw new IllegalStateException("Test schema not configured but do.all-schemas is false");
            }

            String normalizedTestSchema = testSchema.trim().toUpperCase();
            List<String> availableSchemas = stateService.getOracleSchemaNames();

            if (!availableSchemas.contains(normalizedTestSchema)) {
                updateProgress(progressCallback, 100, "Schema not found", "Configured test schema '" + normalizedTestSchema + "' not found in available schemas");
                log.warn("Configured test schema '{}' not found in available schemas: {}", normalizedTestSchema, availableSchemas);
                return new ArrayList<>();
            }

            updateProgress(progressCallback, 0, "Using test schema", "Configuration set to process only schema: " + normalizedTestSchema);
            log.info("Processing only test schema '{}' based on configuration (do.all-schemas=false)", normalizedTestSchema);
            return List.of(normalizedTestSchema);
        }
    }
}