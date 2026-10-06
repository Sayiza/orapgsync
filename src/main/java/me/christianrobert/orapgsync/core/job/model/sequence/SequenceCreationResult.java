package me.christianrobert.orapgsync.core.job.model.sequence;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL sequence creation operation.
 * Tracks created sequences, skipped sequences, and any errors that occurred.
 */
public class SequenceCreationResult {

    private final List<String> createdSequences = new ArrayList<>();
    private final List<String> skippedSequences = new ArrayList<>();
    private final List<SequenceCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created sequence.
     * @param qualifiedSequenceName The fully qualified sequence name (schema.sequencename)
     */
    public void addCreatedSequence(String qualifiedSequenceName) {
        createdSequences.add(qualifiedSequenceName);
    }

    /**
     * Adds a skipped sequence (already exists).
     * @param qualifiedSequenceName The fully qualified sequence name (schema.sequencename)
     */
    public void addSkippedSequence(String qualifiedSequenceName) {
        skippedSequences.add(qualifiedSequenceName);
    }

    /**
     * Adds an error that occurred during sequence creation.
     * @param qualifiedSequenceName The fully qualified sequence name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedSequenceName, String errorMessage, String sqlStatement) {
        errors.add(new SequenceCreationError(qualifiedSequenceName, errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created sequences.
     * @return List of qualified sequence names
     */
    public List<String> getCreatedSequences() {
        return new ArrayList<>(createdSequences);
    }

    /**
     * Gets the list of skipped sequences.
     * @return List of qualified sequence names
     */
    public List<String> getSkippedSequences() {
        return new ArrayList<>(skippedSequences);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of SequenceCreationError objects
     */
    public List<SequenceCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created sequences.
     */
    public int getCreatedCount() {
        return createdSequences.size();
    }

    /**
     * Gets the number of skipped sequences.
     */
    public int getSkippedCount() {
        return skippedSequences.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of sequences processed.
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
     * Error information for a failed sequence creation.
     */
    public static class SequenceCreationError {
        private final String sequenceName;
        private final String errorMessage;
        private final String sqlStatement;

        public SequenceCreationError(String sequenceName, String errorMessage, String sqlStatement) {
            this.sequenceName = sequenceName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getSequenceName() {
            return sequenceName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("SequenceCreationError{sequenceName='%s', error='%s', sql='%s'}",
                    sequenceName, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("SequenceCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
