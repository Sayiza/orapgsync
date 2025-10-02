package me.christianrobert.orapgsync.table.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.table.service.PostgresTableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class PostgresTableMetadataExtractionJob extends AbstractDatabaseExtractionJob<TableMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTableMetadataExtractionJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    protected List<String> getAvailableSchemas() {
        // For PostgreSQL extraction, query the database directly instead of relying on state
        // This ensures we can discover tables even if state is empty
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
        return "TABLE_METADATA";
    }

    @Override
    public Class<TableMetadata> getResultType() {
        return TableMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TableMetadata> results) {
        stateService.setPostgresTableMetadata(results);
    }

    @Override
    protected List<TableMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                         "No schemas available for PostgreSQL table extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                      "Starting PostgreSQL table metadata extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting PostgreSQL table metadata extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        List<TableMetadata> allTableMetadata = new ArrayList<>();

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to PostgreSQL database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
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