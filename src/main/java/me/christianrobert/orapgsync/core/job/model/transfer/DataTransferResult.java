package me.christianrobert.orapgsync.core.job.model.transfer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of data transfer operation from Oracle to PostgreSQL.
 * Tracks transferred tables, skipped tables, and any errors that occurred.
 */
public class DataTransferResult {

    private final List<String> transferredTables = new ArrayList<>();
    private final List<String> skippedTables = new ArrayList<>();
    private final List<DataTransferError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();
    private long totalRowsTransferred = 0;

    /**
     * Adds a successfully transferred table.
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     * @param rowsTransferred Number of rows transferred for this table
     */
    public void addTransferredTable(String qualifiedTableName, long rowsTransferred) {
        transferredTables.add(qualifiedTableName);
        totalRowsTransferred += rowsTransferred;
    }

    /**
     * Adds a skipped table (e.g., empty table or already transferred).
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     */
    public void addSkippedTable(String qualifiedTableName) {
        skippedTables.add(qualifiedTableName);
    }

    /**
     * Adds an error that occurred during data transfer.
     * @param qualifiedTableName The fully qualified table name that failed
     * @param errorMessage The error message
     */
    public void addError(String qualifiedTableName, String errorMessage) {
        errors.add(new DataTransferError(qualifiedTableName, errorMessage));
    }

    /**
     * Gets the list of successfully transferred tables.
     * @return List of qualified table names
     */
    public List<String> getTransferredTables() {
        return new ArrayList<>(transferredTables);
    }

    /**
     * Gets the list of skipped tables.
     * @return List of qualified table names
     */
    public List<String> getSkippedTables() {
        return new ArrayList<>(skippedTables);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of DataTransferError objects
     */
    public List<DataTransferError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully transferred tables.
     */
    public int getTransferredCount() {
        return transferredTables.size();
    }

    /**
     * Gets the number of skipped tables.
     */
    public int getSkippedCount() {
        return skippedTables.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of tables processed.
     */
    public int getTotalProcessed() {
        return getTransferredCount() + getSkippedCount() + getErrorCount();
    }

    /**
     * Gets the total number of rows transferred across all tables.
     */
    public long getTotalRowsTransferred() {
        return totalRowsTransferred;
    }

    /**
     * Checks if the operation was successful (no errors).
     */
    public boolean isSuccessful() {
        return errors.isEmpty();
    }

    /**
     * Checks if there were any errors.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Gets the execution date and time.
     */
    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    /**
     * Gets the execution timestamp for compatibility.
     */
    public LocalDateTime getExecutionTimestamp() {
        return executionDateTime;
    }

    /**
     * Error information for a failed data transfer.
     */
    public static class DataTransferError {
        private final String tableName;
        private final String errorMessage;

        public DataTransferError(String tableName, String errorMessage) {
            this.tableName = tableName;
            this.errorMessage = errorMessage;
        }

        public String getTableName() {
            return tableName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return String.format("DataTransferError{tableName='%s', error='%s'}",
                    tableName, errorMessage);
        }
    }

    @Override
    public String toString() {
        return String.format("DataTransferResult{transferred=%d, skipped=%d, errors=%d, totalRows=%d, successful=%s}",
                getTransferredCount(), getSkippedCount(), getErrorCount(), totalRowsTransferred, isSuccessful());
    }
}
