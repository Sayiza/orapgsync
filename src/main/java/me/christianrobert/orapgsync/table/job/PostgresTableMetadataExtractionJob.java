package me.christianrobert.orapgsync.table.job;

import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import me.christianrobert.orapgsync.table.service.PostgresTableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class PostgresTableMetadataExtractionJob implements Job<List<TableMetadata>> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTableMetadataExtractionJob.class);

    private final String jobId;

    private StateService stateService;
    private PostgresConnectionService postgresConnectionService;
    private ConfigService configService;

    public PostgresTableMetadataExtractionJob() {
        this.jobId = "postgres-table-metadata-extraction-" + UUID.randomUUID().toString();
    }

    public void setStateService(StateService stateService) {
        this.stateService = stateService;
    }

    public void setPostgresConnectionService(PostgresConnectionService postgresConnectionService) {
        this.postgresConnectionService = postgresConnectionService;
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
        return "POSTGRES_TABLE_METADATA_EXTRACTION";
    }

    @Override
    public String getDescription() {
        return "Extract table metadata from PostgreSQL database and store in global state";
    }

    @Override
    public CompletableFuture<List<TableMetadata>> execute(Consumer<JobProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performExtraction(progressCallback);
            } catch (Exception e) {
                log.error("PostgreSQL table metadata extraction failed", e);
                throw new RuntimeException("PostgreSQL table metadata extraction failed: " + e.getMessage(), e);
            }
        });
    }

    private List<TableMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process", "No schemas available for PostgreSQL table extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL table metadata extraction for " + schemasToProcess.size() + " schemas");

        log.info("Starting PostgreSQL table metadata extraction for {} schemas",
                schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        List<TableMetadata> allTableMetadata = new ArrayList<>();

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to PostgreSQL database");

            int totalSchemas = schemasToProcess.size();
            int processedSchemas = 0;

            for (String schema : schemasToProcess) {
                updateProgress(progressCallback,
                    10 + (processedSchemas * 80 / totalSchemas),
                    "Processing schema: " + schema,
                    String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<TableMetadata> schemaTableMetadata = extractTablesForSchema(
                        postgresConnection, schema, progressCallback, processedSchemas, totalSchemas);

                    allTableMetadata.addAll(schemaTableMetadata);

                    log.info("Extracted {} tables from PostgreSQL schema {}", schemaTableMetadata.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract tables for PostgreSQL schema: " + schema, e);
                    updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Error in schema: " + schema,
                        "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            updateProgress(progressCallback, 90, "Storing results", "Saving PostgreSQL table metadata to global state");

            stateService.updatePostgresTableMetadata(allTableMetadata);

            updateProgress(progressCallback, 95, "Preparing summary", "Generating extraction summary");

            Map<String, Integer> schemaSummary = generateSchemaSummary(allTableMetadata);

            String summaryMessage = String.format(
                "Extraction completed: %d tables from %d schemas",
                allTableMetadata.size(),
                schemaSummary.size());

            updateProgress(progressCallback, 100, "Completed", summaryMessage);

            log.info("PostgreSQL table metadata extraction completed successfully: {} tables from {} schemas",
                    allTableMetadata.size(), schemaSummary.size());

            return allTableMetadata;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "PostgreSQL table metadata extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<TableMetadata> extractTablesForSchema(Connection postgresConnection, String schema,
                                                     Consumer<JobProgress> progressCallback,
                                                     int currentSchemaIndex, int totalSchemas) throws Exception {

        List<String> singleSchemaList = List.of(schema);
        return PostgresTableExtractor.extractAllTables(postgresConnection, singleSchemaList);
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
        updateProgress(progressCallback, 0, "Checking configuration", "Determining PostgreSQL schemas to process based on configuration settings");

        // Check configuration settings
        boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
        String testSchema = configService.getConfigValueAsString("do.only-test-schema");

        if (doAllSchemas) {
            updateProgress(progressCallback, 0, "Using all schemas", "Configuration set to process all available PostgreSQL schemas");
            log.info("Processing all PostgreSQL schemas based on configuration (do.all-schemas=true)");
            return stateService.getPostgresSchemaNames();
        } else {
            if (testSchema == null || testSchema.trim().isEmpty()) {
                updateProgress(progressCallback, 100, "Configuration error", "Test schema not configured but do.all-schemas is false");
                log.error("Test schema not configured but do.all-schemas is false");
                throw new IllegalStateException("Test schema not configured but do.all-schemas is false");
            }

            String normalizedTestSchema = testSchema.trim().toLowerCase(); // PostgreSQL uses lowercase schema names
            List<String> availableSchemas = stateService.getPostgresSchemaNames();

            if (!availableSchemas.contains(normalizedTestSchema)) {
                updateProgress(progressCallback, 100, "Schema not found", "Configured test schema '" + normalizedTestSchema + "' not found in available PostgreSQL schemas");
                log.warn("Configured test schema '{}' not found in available PostgreSQL schemas: {}", normalizedTestSchema, availableSchemas);
                return new ArrayList<>();
            }

            updateProgress(progressCallback, 0, "Using test schema", "Configuration set to process only PostgreSQL schema: " + normalizedTestSchema);
            log.info("Processing only test schema '{}' based on configuration (do.all-schemas=false)", normalizedTestSchema);
            return List.of(normalizedTestSchema);
        }
    }
}