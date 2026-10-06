package me.christianrobert.orapgsync.core.job.model.trigger;

import java.util.HashMap;
import java.util.Map;

/**
 * Result object for trigger implementation operations.
 * Tracks which triggers were successfully implemented, skipped, or failed.
 */
public class TriggerImplementationResult {
    private Map<String, TriggerMetadata> implementedTriggers;
    private Map<String, TriggerMetadata> skippedTriggers;
    private Map<String, ErrorInfo> errors;
    private int implementedCount;
    private int skippedCount;
    private int errorCount;

    public TriggerImplementationResult() {
        this.implementedTriggers = new HashMap<>();
        this.skippedTriggers = new HashMap<>();
        this.errors = new HashMap<>();
        this.implementedCount = 0;
        this.skippedCount = 0;
        this.errorCount = 0;
    }

    public void addImplementedTrigger(TriggerMetadata trigger) {
        String key = trigger.getQualifiedName();
        implementedTriggers.put(key, trigger);
        implementedCount++;
    }

    public void addSkippedTrigger(TriggerMetadata trigger) {
        String key = trigger.getQualifiedName();
        skippedTriggers.put(key, trigger);
        skippedCount++;
    }

    public void addError(String triggerName, String errorMessage, String sql) {
        ErrorInfo error = new ErrorInfo(triggerName, errorMessage, sql);
        errors.put(triggerName, error);
        errorCount++;
    }

    // Getters and setters
    public Map<String, TriggerMetadata> getImplementedTriggers() {
        return implementedTriggers;
    }

    public void setImplementedTriggers(Map<String, TriggerMetadata> implementedTriggers) {
        this.implementedTriggers = implementedTriggers;
        this.implementedCount = implementedTriggers.size();
    }

    public Map<String, TriggerMetadata> getSkippedTriggers() {
        return skippedTriggers;
    }

    public void setSkippedTriggers(Map<String, TriggerMetadata> skippedTriggers) {
        this.skippedTriggers = skippedTriggers;
        this.skippedCount = skippedTriggers.size();
    }

    public Map<String, ErrorInfo> getErrors() {
        return errors;
    }

    public void setErrors(Map<String, ErrorInfo> errors) {
        this.errors = errors;
        this.errorCount = errors.size();
    }

    public int getImplementedCount() {
        return implementedCount;
    }

    public void setImplementedCount(int implementedCount) {
        this.implementedCount = implementedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public boolean isSuccessful() {
        return errorCount == 0;
    }

    /**
     * Error information for a failed trigger implementation.
     */
    public static class ErrorInfo {
        private String triggerName;
        private String error;
        private String sql;

        public ErrorInfo(String triggerName, String error, String sql) {
            this.triggerName = triggerName;
            this.error = error;
            this.sql = sql;
        }

        public String getTriggerName() {
            return triggerName;
        }

        public void setTriggerName(String triggerName) {
            this.triggerName = triggerName;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }

    @Override
    public String toString() {
        return String.format("TriggerImplementationResult{implemented=%d, skipped=%d, errors=%d}",
                implementedCount, skippedCount, errorCount);
    }
}
