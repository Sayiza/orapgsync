package me.christianrobert.orapgsync.core.job.model.table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL foreign key index creation operation.
 * Tracks created indexes, skipped indexes, and any errors that occurred.
 *
 * Foreign key indexes are created automatically on FK source columns in PostgreSQL
 * to match Oracle's behavior (Oracle automatically creates indexes on FK columns).
 * This improves query performance and prevents lock escalation during FK operations.
 */
public class FKIndexCreationResult {

    private final List<IndexInfo> createdIndexes = new ArrayList<>();
    private final List<IndexInfo> skippedIndexes = new ArrayList<>();
    private final List<IndexCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created index.
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     * @param indexName The index name
     * @param columnNames The columns included in the index
     */
    public void addCreatedIndex(String qualifiedTableName, String indexName, List<String> columnNames) {
        createdIndexes.add(new IndexInfo(qualifiedTableName, indexName, columnNames));
    }

    /**
     * Adds a skipped index (already exists).
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     * @param indexName The index name
     * @param columnNames The columns included in the index
     * @param reason The reason for skipping
     */
    public void addSkippedIndex(String qualifiedTableName, String indexName, List<String> columnNames, String reason) {
        skippedIndexes.add(new IndexInfo(qualifiedTableName, indexName, columnNames, reason));
    }

    /**
     * Adds an error that occurred during index creation.
     * @param qualifiedTableName The fully qualified table name that failed
     * @param indexName The index name
     * @param columnNames The columns that should be indexed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedTableName, String indexName, List<String> columnNames,
                        String errorMessage, String sqlStatement) {
        errors.add(new IndexCreationError(qualifiedTableName, indexName, columnNames,
                                         errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created indexes.
     * @return List of IndexInfo objects
     */
    public List<IndexInfo> getCreatedIndexes() {
        return new ArrayList<>(createdIndexes);
    }

    /**
     * Gets the list of skipped indexes.
     * @return List of IndexInfo objects
     */
    public List<IndexInfo> getSkippedIndexes() {
        return new ArrayList<>(skippedIndexes);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of IndexCreationError objects
     */
    public List<IndexCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created indexes.
     */
    public int getCreatedCount() {
        return createdIndexes.size();
    }

    /**
     * Gets the number of skipped indexes.
     */
    public int getSkippedCount() {
        return skippedIndexes.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of indexes processed.
     */
    public int getTotalProcessed() {
        return getCreatedCount() + getSkippedCount() + getErrorCount();
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
     * Information about an index (created or skipped).
     */
    public static class IndexInfo {
        private final String tableName;
        private final String indexName;
        private final List<String> columnNames;
        private final String reason; // For skipped indexes

        public IndexInfo(String tableName, String indexName, List<String> columnNames) {
            this(tableName, indexName, columnNames, null);
        }

        public IndexInfo(String tableName, String indexName, List<String> columnNames, String reason) {
            this.tableName = tableName;
            this.indexName = indexName;
            this.columnNames = new ArrayList<>(columnNames);
            this.reason = reason;
        }

        public String getTableName() {
            return tableName;
        }

        public String getIndexName() {
            return indexName;
        }

        public List<String> getColumnNames() {
            return new ArrayList<>(columnNames);
        }

        public String getReason() {
            return reason;
        }

        public String getColumnsDisplay() {
            return String.join(", ", columnNames);
        }

        @Override
        public String toString() {
            return String.format("IndexInfo{table='%s', name='%s', columns=%s}",
                    tableName, indexName, columnNames);
        }
    }

    /**
     * Error information for a failed index creation.
     */
    public static class IndexCreationError {
        private final String tableName;
        private final String indexName;
        private final List<String> columnNames;
        private final String errorMessage;
        private final String sqlStatement;

        public IndexCreationError(String tableName, String indexName, List<String> columnNames,
                                 String errorMessage, String sqlStatement) {
            this.tableName = tableName;
            this.indexName = indexName;
            this.columnNames = new ArrayList<>(columnNames);
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getTableName() {
            return tableName;
        }

        public String getIndexName() {
            return indexName;
        }

        public List<String> getColumnNames() {
            return new ArrayList<>(columnNames);
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        public String getColumnsDisplay() {
            return String.join(", ", columnNames);
        }

        @Override
        public String toString() {
            return String.format("IndexCreationError{table='%s', index='%s', columns=%s, error='%s', sql='%s'}",
                    tableName, indexName, columnNames, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("FKIndexCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
