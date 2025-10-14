package me.christianrobert.orapgsync.core.job.model.view;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of PostgreSQL view implementation verification operation.
 * Verifies that views have been properly implemented (not stubs) and have data.
 *
 * This verification checks:
 * - View exists in PostgreSQL
 * - View is NOT a stub (does not contain "WHERE false")
 * - View returns data (if Oracle view has data)
 * - Optional: Row count comparison between Oracle and PostgreSQL
 */
public class ViewImplementationVerificationResult {

    private final List<VerifiedView> verifiedViews = new ArrayList<>();
    private final List<String> failedViews = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> failureReasons = new HashMap<>();
    private final Map<String, Long> rowCounts = new HashMap<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully verified view implementation.
     * @param qualifiedViewName The fully qualified view name (schema.viewname)
     * @param rowCount The number of rows returned by the view
     */
    public void addVerified(String qualifiedViewName, long rowCount) {
        verifiedViews.add(new VerifiedView(qualifiedViewName, rowCount));
        rowCounts.put(qualifiedViewName, rowCount);
    }

    /**
     * Adds a failed view (still a stub or doesn't exist).
     * @param qualifiedViewName The fully qualified view name
     * @param reason The reason for failure
     */
    public void addFailed(String qualifiedViewName, String reason) {
        failedViews.add(qualifiedViewName);
        failureReasons.put(qualifiedViewName, reason);
    }

    /**
     * Adds a warning (e.g., row count mismatch).
     * @param qualifiedViewName The fully qualified view name
     * @param warning The warning message
     */
    public void addWarning(String qualifiedViewName, String warning) {
        warnings.add(qualifiedViewName + ": " + warning);
    }

    /**
     * Gets the list of verified views.
     */
    public List<VerifiedView> getVerifiedViews() {
        return new ArrayList<>(verifiedViews);
    }

    /**
     * Gets the list of failed views.
     */
    public List<String> getFailedViews() {
        return new ArrayList<>(failedViews);
    }

    /**
     * Gets the list of warnings.
     */
    public List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }

    /**
     * Gets the failure reasons map.
     */
    public Map<String, String> getFailureReasons() {
        return new HashMap<>(failureReasons);
    }

    /**
     * Gets the row counts map.
     */
    public Map<String, Long> getRowCounts() {
        return new HashMap<>(rowCounts);
    }

    /**
     * Gets the number of verified views.
     */
    public int getVerifiedCount() {
        return verifiedViews.size();
    }

    /**
     * Gets the number of failed views.
     */
    public int getFailedCount() {
        return failedViews.size();
    }

    /**
     * Gets the number of warnings.
     */
    public int getWarningCount() {
        return warnings.size();
    }

    /**
     * Gets the total number of views processed.
     */
    public int getTotalProcessed() {
        return getVerifiedCount() + getFailedCount();
    }

    /**
     * Checks if the verification was successful (no failures).
     */
    public boolean isSuccessful() {
        return failedViews.isEmpty();
    }

    /**
     * Checks if there were any failures.
     */
    public boolean hasFailures() {
        return !failedViews.isEmpty();
    }

    /**
     * Checks if there were any warnings.
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
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
     * Verified view information.
     */
    public static class VerifiedView {
        private final String viewName;
        private final long rowCount;

        public VerifiedView(String viewName, long rowCount) {
            this.viewName = viewName;
            this.rowCount = rowCount;
        }

        public String getViewName() {
            return viewName;
        }

        public long getRowCount() {
            return rowCount;
        }

        @Override
        public String toString() {
            return String.format("VerifiedView{viewName='%s', rowCount=%d}", viewName, rowCount);
        }
    }

    @Override
    public String toString() {
        return String.format("ViewImplementationVerificationResult{verified=%d, failed=%d, warnings=%d, successful=%s}",
                getVerifiedCount(), getFailedCount(), getWarningCount(), isSuccessful());
    }
}
