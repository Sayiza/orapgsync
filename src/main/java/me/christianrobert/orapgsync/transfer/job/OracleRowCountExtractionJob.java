package me.christianrobert.orapgsync.transfer.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import me.christianrobert.orapgsync.transfer.service.RowCountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class OracleRowCountExtractionJob extends AbstractDatabaseExtractionJob<RowCountMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleRowCountExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Inject
    private RowCountService rowCountService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "ROW_COUNT";
    }

    @Override
    public Class<RowCountMetadata> getResultType() {
        return RowCountMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<RowCountMetadata> results) {
        stateService.setOracleRowCountMetadata(results);
    }

    @Override
    protected List<RowCountMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Get table metadata from StateService instead of querying database
        List<me.christianrobert.orapgsync.core.job.model.table.TableMetadata> oracleTableMetadata =
            stateService.getOracleTableMetadata();

        if (oracleTableMetadata == null || oracleTableMetadata.isEmpty()) {
            updateProgress(progressCallback, 100, "No tables to process",
                "No Oracle table metadata found in StateService. Please run Oracle table metadata extraction first.");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
            "Starting row count extraction for " + oracleTableMetadata.size() + " tables from StateService");

        log.debug("Starting row count extraction for {} tables from StateService", oracleTableMetadata.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<RowCountMetadata> allRowCounts = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            int totalTables = oracleTableMetadata.size();
            int processedTables = 0;
            long extractionTimestamp = System.currentTimeMillis();

            for (me.christianrobert.orapgsync.core.job.model.table.TableMetadata table : oracleTableMetadata) {
                updateProgress(progressCallback,
                    10 + (processedTables * 85 / totalTables),
                    "Counting rows in: " + table.getSchema() + "." + table.getTableName(),
                    String.format("Table %d of %d", processedTables + 1, totalTables));

                long rowCount = rowCountService.getRowCount(oracleConnection, table.getSchema(), table.getTableName());

                RowCountMetadata rowCountMetadata = new RowCountMetadata(
                    table.getSchema(),
                    table.getTableName(),
                    rowCount,
                    extractionTimestamp
                );

                allRowCounts.add(rowCountMetadata);

                if (rowCount >= 0) {
                    log.debug("Table {}.{} has {} rows", table.getSchema(), table.getTableName(), rowCount);
                } else {
                    log.warn("Table {}.{} had error during row count", table.getSchema(), table.getTableName());
                }

                processedTables++;
            }

            // Sort results by schema and table name for deterministic ordering
            allRowCounts.sort(Comparator
                .comparing(RowCountMetadata::getSchema)
                .thenComparing(RowCountMetadata::getTableName));

            log.debug("Sorted {} row counts for deterministic ordering", allRowCounts.size());

            updateProgress(progressCallback, 95, "Finalizing", "Row count extraction completed for " + allRowCounts.size() + " tables");

            return allRowCounts;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Row count extraction failed: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(List<RowCountMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        long totalRows = 0;
        int errorTables = 0;

        for (RowCountMetadata rowCount : results) {
            String schema = rowCount.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);

            if (rowCount.getRowCount() >= 0) {
                totalRows += rowCount.getRowCount();
            } else {
                errorTables++;
            }
        }

        String baseMessage = String.format("Row count extraction completed: %d tables from %d schemas, %,d total rows",
                           results.size(), schemaSummary.size(), totalRows);

        if (errorTables > 0) {
            baseMessage += String.format(" (%d tables had errors)", errorTables);
        }

        return baseMessage;
    }
}