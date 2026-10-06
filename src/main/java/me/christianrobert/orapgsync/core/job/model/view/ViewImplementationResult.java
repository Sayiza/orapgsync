package me.christianrobert.orapgsync.core.job.model.view;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL view implementation operation (Phase 2).
 * Tracks implemented views (replacing stubs with actual SQL), skipped views, and errors.
 *
 * This is Phase 2 of view migration:
 * - Phase 1: View stubs (empty result sets with correct structure)
 * - Phase 2: View implementation (actual transformed SQL from Oracle)
 */
public class ViewImplementationResult {

    private final List<String> implementedViews = new ArrayList<>();
    private final List<String> skippedViews = new ArrayList<>();
    private final List<ViewImplementationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully implemented view (replaced stub with actual SQL).
     * @param qualifiedViewName The fully qualified view name (schema.viewname)
     */
    public void addImplementedView(String qualifiedViewName) {
        implementedViews.add(qualifiedViewName);
    }

    /**
     * Adds a skipped view (already implemented or no SQL available).
     * @param qualifiedViewName The fully qualified view name (schema.viewname)
     */
    public void addSkippedView(String qualifiedViewName) {
        skippedViews.add(qualifiedViewName);
    }

    /**
     * Adds an error that occurred during view implementation.
     * @param qualifiedViewName The fully qualified view name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedViewName, String errorMessage, String sqlStatement) {
        errors.add(new ViewImplementationError(qualifiedViewName, errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully implemented views.
     * @return List of qualified view names
     */
    public List<String> getImplementedViews() {
        return new ArrayList<>(implementedViews);
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
     * @return List of ViewImplementationError objects
     */
    public List<ViewImplementationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully implemented views.
     */
    public int getImplementedCount() {
        return implementedViews.size();
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
        return getImplementedCount() + getSkippedCount() + getErrorCount();
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
     * Error information for a failed view implementation.
     */
    public static class ViewImplementationError {
        private final String viewName;
        private final String errorMessage;
        private final String sqlStatement;

        public ViewImplementationError(String viewName, String errorMessage, String sqlStatement) {
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
            return String.format("ViewImplementationError{viewName='%s', error='%s', sql='%s'}",
                    viewName, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("ViewImplementationResult{implemented=%d, skipped=%d, errors=%d, successful=%s}",
                getImplementedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
