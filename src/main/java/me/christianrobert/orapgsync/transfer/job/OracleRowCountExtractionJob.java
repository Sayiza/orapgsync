package me.christianrobert.orapgsync.transfer.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.transfer.model.RowCountMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class OracleRowCountExtractionJob extends AbstractDatabaseExtractionJob<RowCountMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleRowCountExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

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
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process", "No schemas available for row count extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing", "Starting row count extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting row count extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<RowCountMetadata> allRowCounts = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            // First, get all tables for the schemas
            List<TableInfo> tablesInfo = getTablesForSchemas(oracleConnection, validSchemas, progressCallback);

            if (tablesInfo.isEmpty()) {
                updateProgress(progressCallback, 100, "No tables found", "No tables found in the specified schemas");
                return new ArrayList<>();
            }

            updateProgress(progressCallback, 20, "Found tables", "Found " + tablesInfo.size() + " tables, starting row count extraction");

            int totalTables = tablesInfo.size();
            int processedTables = 0;
            long extractionTimestamp = System.currentTimeMillis();

            for (TableInfo tableInfo : tablesInfo) {
                updateProgress(progressCallback,
                    20 + (processedTables * 75 / totalTables),
                    "Counting rows in: " + tableInfo.schema + "." + tableInfo.tableName,
                    String.format("Table %d of %d", processedTables + 1, totalTables));

                try {
                    long rowCount = getRowCountForTable(oracleConnection, tableInfo.schema, tableInfo.tableName);

                    RowCountMetadata rowCountMetadata = new RowCountMetadata(
                        tableInfo.schema,
                        tableInfo.tableName,
                        rowCount,
                        extractionTimestamp
                    );

                    allRowCounts.add(rowCountMetadata);

                    log.debug("Table {}.{} has {} rows", tableInfo.schema, tableInfo.tableName, rowCount);

                } catch (Exception e) {
                    log.error("Failed to get row count for table: {}.{}", tableInfo.schema, tableInfo.tableName, e);
                    // Add a row count of -1 to indicate error
                    RowCountMetadata errorMetadata = new RowCountMetadata(
                        tableInfo.schema,
                        tableInfo.tableName,
                        -1,
                        extractionTimestamp
                    );
                    allRowCounts.add(errorMetadata);
                }

                processedTables++;
            }

            updateProgress(progressCallback, 95, "Finalizing", "Row count extraction completed for " + allRowCounts.size() + " tables");

            return allRowCounts;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Row count extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<TableInfo> getTablesForSchemas(Connection connection, List<String> schemas,
                                              Consumer<JobProgress> progressCallback) throws Exception {
        List<TableInfo> tables = new ArrayList<>();

        String sql = "SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE OWNER IN (" +
                     String.join(",", schemas.stream().map(s -> "?").toArray(String[]::new)) + ") " +
                     "ORDER BY OWNER, TABLE_NAME";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < schemas.size(); i++) {
                stmt.setString(i + 1, schemas.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String owner = rs.getString("OWNER");
                    String tableName = rs.getString("TABLE_NAME");
                    tables.add(new TableInfo(owner, tableName));
                }
            }
        }

        log.info("Found {} tables across {} schemas", tables.size(), schemas.size());
        return tables;
    }

    private long getRowCountForTable(Connection connection, String schema, String tableName) throws Exception {
        // Use COUNT(*) for precise row count
        String sql = "SELECT COUNT(*) as ROW_COUNT FROM " + schema + "." + tableName;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong("ROW_COUNT");
            } else {
                throw new RuntimeException("No result returned from count query");
            }
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

    /**
     * Simple holder class for table information.
     */
    private static class TableInfo {
        final String schema;
        final String tableName;

        TableInfo(String schema, String tableName) {
            this.schema = schema;
            this.tableName = tableName;
        }
    }
}