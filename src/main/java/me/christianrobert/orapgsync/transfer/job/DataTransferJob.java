package me.christianrobert.orapgsync.transfer.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transfer.service.CsvDataTransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Job for transferring data from Oracle to PostgreSQL.
 * This is the main data transfer job that coordinates the migration of table data.
 */
@Dependent
public class DataTransferJob extends AbstractDatabaseWriteJob<DataTransferResult> {

    private static final Logger log = LoggerFactory.getLogger(DataTransferJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private CsvDataTransferService csvDataTransferService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "DATA_TRANSFER";
    }

    @Override
    public Class<DataTransferResult> getResultType() {
        return DataTransferResult.class;
    }

    @Override
    protected void saveResultsToState(DataTransferResult result) {
        stateService.setDataTransferResult(result);
    }

    @Override
    protected DataTransferResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting data transfer from Oracle to PostgreSQL");

        // Get Oracle tables from state
        List<TableMetadata> oracleTables = stateService.getOracleTableMetadata();
        if (oracleTables == null || oracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No tables to process",
                    "No Oracle tables found in state. Please extract Oracle table metadata first.");
            log.warn("No Oracle tables found in state for data transfer");
            return new DataTransferResult();
        }

        // Filter valid tables (exclude system schemas)
        List<TableMetadata> validOracleTables = filterValidTables(oracleTables);

        updateProgress(progressCallback, 10, "Analyzing tables",
                String.format("Found %d Oracle tables, %d are valid for data transfer",
                        oracleTables.size(), validOracleTables.size()));

        DataTransferResult result = new DataTransferResult();

        if (validOracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid tables", "No valid Oracle tables to transfer");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to databases", "Establishing database connections");

        try (Connection oracleConnection = oracleConnectionService.getConnection();
             Connection postgresConnection = postgresConnectionService.getConnection()) {

            // Disable auto-commit for batch operations
            postgresConnection.setAutoCommit(false);

            updateProgress(progressCallback, 30, "Connected", "Successfully connected to both databases");

            log.info("Data transfer job initialized successfully");
            log.info("Ready to transfer data for {} tables", validOracleTables.size());

            int totalTables = validOracleTables.size();
            int processedTables = 0;

            for (TableMetadata table : validOracleTables) {
                String qualifiedTableName = table.getSchema() + "." + table.getTableName();

                updateProgress(progressCallback,
                        30 + (processedTables * 65 / totalTables),
                        "Transferring: " + qualifiedTableName,
                        String.format("Table %d of %d", processedTables + 1, totalTables));

                try {
                    // Attempt to transfer the table
                    long rowsTransferred = csvDataTransferService.transferTable(
                            oracleConnection, postgresConnection, table);

                    if (rowsTransferred == 0) {
                        result.addSkippedTable(qualifiedTableName);
                        log.info("Table {} skipped (no data or already transferred)", qualifiedTableName);
                    } else {
                        result.addTransferredTable(qualifiedTableName, rowsTransferred);
                        log.info("Table {} transferred: {} rows", qualifiedTableName, rowsTransferred);
                    }

                } catch (Exception e) {
                    // Log error and continue with next table (skip tables with errors)
                    log.error("Failed to transfer table: {}", qualifiedTableName, e);
                    result.addError(qualifiedTableName, e.getMessage());
                }

                processedTables++;
            }

            updateProgress(progressCallback, 95, "Finalizing", "Data transfer operation completed");

            log.info("Data transfer job completed: {}", result);

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Data transfer failed: " + e.getMessage());
            log.error("Data transfer job failed", e);
            throw e;
        }
    }

    /**
     * Filters tables to exclude system schemas.
     */
    private List<TableMetadata> filterValidTables(List<TableMetadata> tables) {
        List<TableMetadata> validTables = new ArrayList<>();
        for (TableMetadata table : tables) {
            if (!filterValidSchemas(List.of(table.getSchema())).isEmpty()) {
                validTables.add(table);
            }
        }
        return validTables;
    }

    @Override
    protected String generateSummaryMessage(DataTransferResult result) {
        if (result.getTotalProcessed() == 0) {
            return "No tables processed for data transfer";
        }

        String baseMessage = String.format(
                "Data transfer completed: %d tables transferred, %d skipped, %,d total rows",
                result.getTransferredCount(),
                result.getSkippedCount(),
                result.getTotalRowsTransferred()
        );

        if (result.hasErrors()) {
            baseMessage += String.format(" (%d tables had errors)", result.getErrorCount());
        }

        return baseMessage;
    }
}
