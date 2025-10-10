package me.christianrobert.orapgsync.sequence.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.sequence.SequenceMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.sequence.service.PostgresSequenceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class PostgresSequenceExtractionJob extends AbstractDatabaseExtractionJob<SequenceMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresSequenceExtractionJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    protected List<String> getAvailableSchemas() {
        // For PostgreSQL extraction, query the database directly instead of relying on state
        // This ensures we can discover sequences even if state is empty
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
        return "SEQUENCE";
    }

    @Override
    public Class<SequenceMetadata> getResultType() {
        return SequenceMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<SequenceMetadata> results) {
        stateService.setPostgresSequenceMetadata(results);
    }

    @Override
    protected List<SequenceMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for PostgreSQL sequence extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL sequence extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting PostgreSQL sequence extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        List<SequenceMetadata> allSequenceMetadata = new ArrayList<>();

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected",
                    "Successfully connected to PostgreSQL database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<SequenceMetadata> schemaSequenceMetadata =
                            PostgresSequenceExtractor.extractAllSequences(postgresConnection, List.of(schema));

                    allSequenceMetadata.addAll(schemaSequenceMetadata);

                    log.info("Extracted {} sequences from PostgreSQL schema {}",
                            schemaSequenceMetadata.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract sequences for PostgreSQL schema: " + schema, e);
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
                    "PostgreSQL sequence extraction failed: " + e.getMessage());
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
