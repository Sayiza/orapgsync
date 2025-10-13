package me.christianrobert.orapgsync.core.job.model.viewdefinition;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL view stub creation operation.
 * Tracks created view stubs, skipped views, and any errors that occurred.
 *
 * View stubs are created with correct column structure but return empty result sets
 * (SELECT NULL::type AS column... WHERE false) to support future PL/SQL function migration.
 */
public class ViewStubCreationResult {

    private final List<String> createdViews = new ArrayList<>();
    private final List<String> skippedViews = new ArrayList<>();
    private final List<ViewCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created view stub.
     * @param qualifiedViewName The fully qualified view name (schema.viewname)
     */
    public void addCreatedView(String qualifiedViewName) {
        createdViews.add(qualifiedViewName);
    }

    /**
     * Adds a skipped view (already exists).
     * @param qualifiedViewName The fully qualified view name (schema.viewname)
     */
    public void addSkippedView(String qualifiedViewName) {
        skippedViews.add(qualifiedViewName);
    }

    /**
     * Adds an error that occurred during view creation.
     * @param qualifiedViewName The fully qualified view name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedViewName, String errorMessage, String sqlStatement) {
        errors.add(new ViewCreationError(qualifiedViewName, errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created views.
     * @return List of qualified view names
     */
    public List<String> getCreatedViews() {
        return new ArrayList<>(createdViews);
    }

    /**
     * Gets the list of skipped views.
     * @return List of qualified view names
     */
    public List<String> getSkippedViews() {
        return new ArrayList<>(skippedViews);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of ViewCreationError objects
     */
    public List<ViewCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created views.
     */
    public int getCreatedCount() {
        return createdViews.size();
    }

    /**
     * Gets the number of skipped views.
     */
    public int getSkippedCount() {
        return skippedViews.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of views processed.
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
     * Error information for a failed view creation.
     */
    public static class ViewCreationError {
        private final String viewName;
        private final String errorMessage;
        private final String sqlStatement;

        public ViewCreationError(String viewName, String errorMessage, String sqlStatement) {
            this.viewName = viewName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getViewName() {
            return viewName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("ViewCreationError{viewName='%s', error='%s', sql='%s'}",
                    viewName, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("ViewStubCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
