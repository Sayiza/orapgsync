package me.christianrobert.orapgsync.core.job.model.view;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Result of unified PostgreSQL view verification operation.
 * Verifies all views (both stubs and implementations) and returns their DDL for manual inspection.
 *
 * This verification:
 * - Queries PostgreSQL directly (no state dependency)
 * - Extracts view DDL using pg_get_viewdef()
 * - Auto-detects status (STUB vs IMPLEMENTED) from DDL content
 * - Groups results by schema for easy navigation
 * - Does NOT execute row counts (performance optimization)
 */
public class ViewVerificationResult {

    private final Map<String, List<ViewInfo>> viewsBySchema = new LinkedHashMap<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a view to the verification result.
     */
    public void addView(String schema, ViewInfo viewInfo) {
        viewsBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(viewInfo);
    }

    /**
     * Gets all views grouped by schema.
     */
    public Map<String, List<ViewInfo>> getViewsBySchema() {
        return new LinkedHashMap<>(viewsBySchema);
    }

    /**
     * Gets the list of all schemas.
     */
    public List<String> getSchemas() {
        return new ArrayList<>(viewsBySchema.keySet());
    }

    /**
     * Gets views for a specific schema.
     */
    public List<ViewInfo> getViewsForSchema(String schema) {
        return viewsBySchema.getOrDefault(schema, new ArrayList<>());
    }

    /**
     * Gets the total number of views across all schemas.
     */
    public int getTotalViews() {
        return viewsBySchema.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    /**
     * Gets the total number of stub views.
     */
    public int getStubCount() {
        return (int) viewsBySchema.values().stream()
                .flatMap(List::stream)
                .filter(v -> v.getStatus() == ViewStatus.STUB)
                .count();
    }

    /**
     * Gets the total number of implemented views.
     */
    public int getImplementedCount() {
        return (int) viewsBySchema.values().stream()
                .flatMap(List::stream)
                .filter(v -> v.getStatus() == ViewStatus.IMPLEMENTED)
                .count();
    }

    /**
     * Gets the total number of views with errors.
     */
    public int getErrorCount() {
        return (int) viewsBySchema.values().stream()
                .flatMap(List::stream)
                .filter(v -> v.getStatus() == ViewStatus.ERROR)
                .count();
    }

    /**
     * Gets the execution timestamp.
     */
    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    /**
     * Information about a single view.
     */
    public static class ViewInfo {
        private final String viewName;
        private ViewStatus status;
        private String viewDdl;
        private String errorMessage;

        public ViewInfo(String viewName) {
            this.viewName = viewName;
        }

        public String getViewName() {
            return viewName;
        }

        public ViewStatus getStatus() {
            return status;
        }

        public void setStatus(ViewStatus status) {
            this.status = status;
        }

        public String getViewDdl() {
            return viewDdl;
        }

        public void setViewDdl(String viewDdl) {
            this.viewDdl = viewDdl;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString() {
            return String.format("ViewInfo{viewName='%s', status=%s}", viewName, status);
        }
    }

    /**
     * Status of a view.
     */
    public enum ViewStatus {
        STUB,         // Contains "WHERE false" pattern - placeholder not yet implemented
        IMPLEMENTED,  // Full view implementation with actual SQL
        ERROR         // Could not retrieve DDL or view has errors
    }

    @Override
    public String toString() {
        return String.format("ViewVerificationResult{total=%d, implemented=%d, stubs=%d, errors=%d, schemas=%d}",
                getTotalViews(), getImplementedCount(), getStubCount(), getErrorCount(), viewsBySchema.size());
    }
}
