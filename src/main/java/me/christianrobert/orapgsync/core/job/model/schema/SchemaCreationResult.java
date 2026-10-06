package me.christianrobert.orapgsync.core.job.model.schema;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result model for schema creation operations.
 * Tracks which schemas were created, which already existed, and any errors encountered.
 */
public class SchemaCreationResult {

    private final List<String> createdSchemas;
    private final List<String> skippedSchemas;
    private final List<SchemaCreationError> errors;
    private final long executionTimestamp;
    private final LocalDateTime executionDateTime;

    public SchemaCreationResult() {
        this.createdSchemas = new ArrayList<>();
        this.skippedSchemas = new ArrayList<>();
        this.errors = new ArrayList<>();
        this.executionTimestamp = System.currentTimeMillis();
        this.executionDateTime = LocalDateTime.now();
    }

    public void addCreatedSchema(String schemaName) {
        createdSchemas.add(schemaName);
    }

    public void addSkippedSchema(String schemaName) {
        skippedSchemas.add(schemaName);
    }

    public void addError(String schemaName, String errorMessage, String sqlStatement) {
        errors.add(new SchemaCreationError(schemaName, errorMessage, sqlStatement));
    }

    public List<String> getCreatedSchemas() {
        return new ArrayList<>(createdSchemas);
    }

    public List<String> getSkippedSchemas() {
        return new ArrayList<>(skippedSchemas);
    }

    public List<SchemaCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    public int getCreatedCount() {
        return createdSchemas.size();
    }

    public int getSkippedCount() {
        return skippedSchemas.size();
    }

    public int getErrorCount() {
        return errors.size();
    }

    public int getTotalProcessed() {
        return getCreatedCount() + getSkippedCount() + getErrorCount();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean isSuccessful() {
        return !hasErrors();
    }

    public long getExecutionTimestamp() {
        return executionTimestamp;
    }

    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    @Override
    public String toString() {
        return String.format("SchemaCreationResult{created=%d, skipped=%d, errors=%d, timestamp=%s}",
                           getCreatedCount(), getSkippedCount(), getErrorCount(), executionDateTime);
    }

    /**
     * Error information for failed schema creation attempts.
     */
    public static class SchemaCreationError {
        private final String schemaName;
        private final String errorMessage;
        private final String sqlStatement;

        public SchemaCreationError(String schemaName, String errorMessage, String sqlStatement) {
            this.schemaName = schemaName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("SchemaCreationError{schema='%s', error='%s', sql='%s'}",
                               schemaName, errorMessage, sqlStatement);
        }
    }
}