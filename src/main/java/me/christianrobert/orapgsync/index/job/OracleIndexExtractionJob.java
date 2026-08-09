package me.christianrobert.orapgsync.index.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.index.service.OracleIndexExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Extracts Oracle indexes that are not backed by a primary key or unique constraint.
 *
 * <p>Constraint-backed indexes are excluded during extraction - PostgreSQL creates those itself
 * as part of constraint creation. See {@link OracleIndexExtractor} for the exclusion rule and the
 * handling of Oracle's hidden descending-index columns.</p>
 */
@Dependent
public class OracleIndexExtractionJob extends AbstractDatabaseExtractionJob<IndexMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleIndexExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Inject
    private OracleIndexExtractor oracleIndexExtractor;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "INDEX";
    }

    @Override
    public Class<IndexMetadata> getResultType() {
        return IndexMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<IndexMetadata> results) {
        stateService.setOracleIndexMetadata(results);
    }

    @Override
    protected List<IndexMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for index extraction based on current configuration");
            return new ArrayList<>();
        }

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        updateProgress(progressCallback, 0, "Initializing",
                "Starting index extraction for " + validSchemas.size() + " schemas");

        Map<String, Set<String>> tablesBySchema = indexTableNamesBySchema();

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<IndexMetadata> allIndexes = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                Set<String> knownTables = tablesBySchema.getOrDefault(schema.toLowerCase(), Set.of());
                allIndexes.addAll(oracleIndexExtractor.extractIndexes(oracleConnection, schema, knownTables));

                processedSchemas++;
            }
        }

        // Deterministic ordering regardless of execution plan (project convention)
        allIndexes.sort(Comparator
                .comparing(IndexMetadata::getSchema, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(IndexMetadata::getTableName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(IndexMetadata::getIndexName, Comparator.nullsLast(Comparator.naturalOrder())));

        log.info("Oracle index extraction completed: {} indexes across {} schemas",
                allIndexes.size(), validSchemas.size());

        return allIndexes;
    }

    /**
     * Table names that were actually migrated, keyed by schema. Indexes on anything else are
     * skipped, because their table will not exist in PostgreSQL.
     */
    private Map<String, Set<String>> indexTableNamesBySchema() {
        Map<String, Set<String>> tablesBySchema = new HashMap<>();
        List<TableMetadata> tables = stateService.getOracleTableMetadata();
        if (tables == null) {
            return tablesBySchema;
        }

        for (TableMetadata table : tables) {
            if (table.getSchema() == null || table.getTableName() == null) {
                continue;
            }
            tablesBySchema
                    .computeIfAbsent(table.getSchema().toLowerCase(), k -> new HashSet<>())
                    .add(table.getTableName().toLowerCase());
        }
        return tablesBySchema;
    }

    @Override
    protected String generateSummaryMessage(List<IndexMetadata> results) {
        long functionBased = results.stream().filter(IndexMetadata::isFunctionBased).count();
        long unique = results.stream().filter(IndexMetadata::isUnique).count();

        return String.format("Index extraction completed: %d indexes (%d unique, %d function-based)",
                results.size(), unique, functionBased);
    }
}
