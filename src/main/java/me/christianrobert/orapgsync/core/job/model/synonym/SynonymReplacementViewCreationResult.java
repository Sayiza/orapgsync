package me.christianrobert.orapgsync.core.job.model.synonym;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL synonym replacement view creation operation.
 * Tracks created views, skipped synonyms, and any errors that occurred.
 *
 * Synonym replacement views are PostgreSQL views that emulate Oracle synonym behavior
 * for external applications (Java/JDBC) that reference database objects through synonyms.
 * Pattern: CREATE VIEW synonym_schema.synonym_name AS SELECT * FROM target_schema.target_name
 */
public class SynonymReplacementViewCreationResult {

    private final List<String> createdViews = new ArrayList<>();
    private final List<String> skippedSynonyms = new ArrayList<>();
    private final List<SynonymViewCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created synonym replacement view.
     * @param qualifiedViewName The fully qualified view name (schema.viewname)
     * @param targetName The target object reference (target_schema.target_name)
     */
    public void addCreatedView(String qualifiedViewName, String targetName) {
        createdViews.add(qualifiedViewName + " -> " + targetName);
    }

    /**
     * Adds a skipped synonym (target doesn't exist, already exists, or is a PUBLIC synonym).
     * @param qualifiedSynonymName The fully qualified synonym name (schema.synonymname)
     * @param reason The reason for skipping
     */
    public void addSkippedSynonym(String qualifiedSynonymName, String reason) {
        skippedSynonyms.add(qualifiedSynonymName + " (" + reason + ")");
    }

    /**
     * Adds an error that occurred during view creation.
     * @param synonymName The fully qualified synonym name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String synonymName, String errorMessage, String sqlStatement) {
        errors.add(new SynonymViewCreationError(synonymName, errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created views.
     * @return List of qualified view names with their targets
     */
    public List<String> getCreatedViews() {
        return new ArrayList<>(createdViews);
    }

    /**
     * Gets the list of skipped synonyms.
     * @return List of qualified synonym names with skip reasons
     */
    public List<String> getSkippedSynonyms() {
        return new ArrayList<>(skippedSynonyms);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of SynonymViewCreationError objects
     */
    public List<SynonymViewCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created views.
     */
    public int getCreatedCount() {
        return createdViews.size();
    }

    /**
     * Gets the number of skipped synonyms.
     */
    public int getSkippedCount() {
        return skippedSynonyms.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of synonyms processed.
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
     * Error information for a failed synonym replacement view creation.
     */
    public static class SynonymViewCreationError {
        private final String synonymName;
        private final String errorMessage;
        private final String sqlStatement;

        public SynonymViewCreationError(String synonymName, String errorMessage, String sqlStatement) {
            this.synonymName = synonymName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getSynonymName() {
            return synonymName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("SynonymViewCreationError{synonymName='%s', error='%s', sql='%s'}",
                    synonymName, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("SynonymReplacementViewCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
