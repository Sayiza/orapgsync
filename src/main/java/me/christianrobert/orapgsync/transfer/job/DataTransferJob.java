package me.christianrobert.orapgsync.transfer.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.core.tools.TableMetadataNormalizer;
import me.christianrobert.orapgsync.transfer.service.ParallelTableTransferService;
import me.christianrobert.orapgsync.transfer.service.TransferOrdering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Job for transferring data from Oracle to PostgreSQL.
 * This is the main data transfer job that coordinates the migration of table data.
 *
 * <p>Tables are transferred concurrently by {@link ParallelTableTransferService}; the number of
 * workers comes from the {@value #WORKER_COUNT_CONFIG_KEY} configuration value.</p>
 */
@Dependent
public class DataTransferJob extends AbstractDatabaseWriteJob<DataTransferResult> {

    private static final Logger log = LoggerFactory.getLogger(DataTransferJob.class);

    public static final String WORKER_COUNT_CONFIG_KEY = "transfer.parallel-workers";
    public static final int DEFAULT_WORKER_COUNT = 4;

    /** Progress span reserved for the table transfers themselves. */
    private static final int PROGRESS_TRANSFER_START = 30;
    private static final int PROGRESS_TRANSFER_SPAN = 65;

    @Inject
    private ParallelTableTransferService parallelTableTransferService;

    @Inject
    private TableMetadataNormalizer tableMetadataNormalizer;

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

        updateProgress(progressCallback, 10, "Normalizing tables",
                String.format("Found %d Oracle tables, %d are valid for data transfer",
                        oracleTables.size(), validOracleTables.size()));

        // Normalize tables by resolving all synonym references in column types
        // This ensures data transfer correctly classifies types for serialization
        List<TableMetadata> normalizedTables = tableMetadataNormalizer.normalizeTableMetadata(validOracleTables);

        updateProgress(progressCallback, 15, "Analyzing tables",
                String.format("Normalized %d tables for data transfer", normalizedTables.size()));

        if (normalizedTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid tables", "No valid Oracle tables to transfer");
            return new DataTransferResult();
        }

        // Largest tables first so the run does not end with one worker on a huge table while the
        // others idle
        List<TableMetadata> orderedTables = TransferOrdering.largestFirst(
                normalizedTables, stateService.getOracleRowCountMetadata());

        int workerCount = resolveWorkerCount();
        int totalTables = orderedTables.size();

        updateProgress(progressCallback, PROGRESS_TRANSFER_START, "Transferring data",
                String.format("Transferring %d tables with %d parallel workers", totalTables, workerCount));

        try {
            DataTransferResult result = parallelTableTransferService.transferTables(orderedTables, workerCount,
                    (completed, total, outcome) -> updateProgress(progressCallback,
                            PROGRESS_TRANSFER_START + (completed * PROGRESS_TRANSFER_SPAN / total),
                            "Transferred: " + outcome.qualifiedTableName(),
                            String.format("Table %d of %d", completed, total)));

            updateProgress(progressCallback, 95, "Finalizing", "Data transfer operation completed");

            log.debug("Data transfer job completed: {}", result);

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Data transfer failed: " + e.getMessage());
            log.error("Data transfer job failed", e);
            throw e;
        }
    }

    /**
     * Reads the configured worker count, falling back to the default if it is missing or not a
     * number. Out-of-range values are clamped by the transfer service.
     */
    private int resolveWorkerCount() {
        Object configured = configService.getConfigValue(WORKER_COUNT_CONFIG_KEY);
        if (configured == null) {
            return DEFAULT_WORKER_COUNT;
        }

        try {
            return Integer.parseInt(configured.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid {} value '{}', falling back to {}",
                    WORKER_COUNT_CONFIG_KEY, configured, DEFAULT_WORKER_COUNT);
            return DEFAULT_WORKER_COUNT;
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
