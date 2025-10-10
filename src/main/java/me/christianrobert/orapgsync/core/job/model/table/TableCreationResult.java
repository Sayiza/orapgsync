package me.christianrobert.orapgsync.core.job.model.table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL table creation operation.
 * Tracks created tables, skipped tables, and any errors that occurred.
 */
public class TableCreationResult {

    private final List<String> createdTables = new ArrayList<>();
    private final List<String> skippedTables = new ArrayList<>();
    private final List<TableCreationError> errors = new ArrayList<>();
    private final List<UnmappedDefaultWarning> unmappedDefaults = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created table.
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     */
    public void addCreatedTable(String qualifiedTableName) {
        createdTables.add(qualifiedTableName);
    }

    /**
     * Adds a skipped table (already exists).
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     */
    public void addSkippedTable(String qualifiedTableName) {
        skippedTables.add(qualifiedTableName);
    }

    /**
     * Adds an error that occurred during table creation.
     * @param qualifiedTableName The fully qualified table name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedTableName, String errorMessage, String sqlStatement) {
        errors.add(new TableCreationError(qualifiedTableName, errorMessage, sqlStatement));
    }

    /**
     * Adds a warning about an unmapped default value that was skipped.
     * @param qualifiedTableName The fully qualified table name
     * @param columnName The column name with unmapped default
     * @param oracleDefault The original Oracle default expression
     * @param note Explanation of why it was skipped
     */
    public void addUnmappedDefault(String qualifiedTableName, String columnName,
                                   String oracleDefault, String note) {
        unmappedDefaults.add(new UnmappedDefaultWarning(qualifiedTableName, columnName, oracleDefault, note));
    }

    /**
     * Gets the list of successfully created tables.
     * @return List of qualified table names
     */
    public List<String> getCreatedTables() {
        return new ArrayList<>(createdTables);
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
     * @return List of TableCreationError objects
     */
    public List<TableCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the list of unmapped default value warnings.
     * @return List of UnmappedDefaultWarning objects
     */
    public List<UnmappedDefaultWarning> getUnmappedDefaults() {
        return new ArrayList<>(unmappedDefaults);
    }

    /**
     * Gets the number of successfully created tables.
     */
    public int getCreatedCount() {
        return createdTables.size();
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
     * Gets the number of unmapped defaults.
     */
    public int getUnmappedDefaultCount() {
        return unmappedDefaults.size();
    }

    /**
     * Gets the total number of tables processed.
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
     * Error information for a failed table creation.
     */
    public static class TableCreationError {
        private final String tableName;
        private final String errorMessage;
        private final String sqlStatement;

        public TableCreationError(String tableName, String errorMessage, String sqlStatement) {
            this.tableName = tableName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getTableName() {
            return tableName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("TableCreationError{tableName='%s', error='%s', sql='%s'}",
                    tableName, errorMessage, sqlStatement);
        }
    }

    /**
     * Warning information for an unmapped default value.
     * These defaults were skipped during table creation and require manual review
     * or future automated transformation.
     */
    public static class UnmappedDefaultWarning {
        private final String tableName;
        private final String columnName;
        private final String oracleDefault;
        private final String note;

        public UnmappedDefaultWarning(String tableName, String columnName,
                                      String oracleDefault, String note) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.oracleDefault = oracleDefault;
            this.note = note;
        }

        public String getTableName() {
            return tableName;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getOracleDefault() {
            return oracleDefault;
        }

        public String getNote() {
            return note;
        }

        @Override
        public String toString() {
            return String.format("UnmappedDefaultWarning{table='%s', column='%s', oracleDefault='%s', note='%s'}",
                    tableName, columnName, oracleDefault, note);
        }
    }

    @Override
    public String toString() {
        return String.format("TableCreationResult{created=%d, skipped=%d, errors=%d, unmappedDefaults=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), getUnmappedDefaultCount(), isSuccessful());
    }
}