package me.christianrobert.orapgsync.core.job.model.function;

import java.util.HashMap;
import java.util.Map;

/**
 * Result object for standalone function/procedure implementation operations.
 * Tracks which standalone functions were successfully implemented, skipped, or failed.
 * This is used for Phase 2 implementation (replacing stubs with actual PL/pgSQL logic).
 */
public class FunctionImplementationResult {
    private Map<String, FunctionMetadata> implementedFunctions;
    private Map<String, FunctionMetadata> skippedFunctions;
    private Map<String, ErrorInfo> errors;
    private int implementedCount;
    private int skippedCount;
    private int errorCount;

    public FunctionImplementationResult() {
        this.implementedFunctions = new HashMap<>();
        this.skippedFunctions = new HashMap<>();
        this.errors = new HashMap<>();
        this.implementedCount = 0;
        this.skippedCount = 0;
        this.errorCount = 0;
    }

    public void addImplementedFunction(FunctionMetadata function) {
        String key = function.getQualifiedName();
        implementedFunctions.put(key, function);
        implementedCount++;
    }

    public void addSkippedFunction(FunctionMetadata function) {
        String key = function.getQualifiedName();
        skippedFunctions.put(key, function);
        skippedCount++;
    }

    public void addError(String functionName, String errorMessage, String sql) {
        ErrorInfo error = new ErrorInfo(functionName, errorMessage, sql);
        errors.put(functionName, error);
        errorCount++;
    }

    // Getters and setters
    public Map<String, FunctionMetadata> getImplementedFunctions() {
        return implementedFunctions;
    }

    public void setImplementedFunctions(Map<String, FunctionMetadata> implementedFunctions) {
        this.implementedFunctions = implementedFunctions;
        this.implementedCount = implementedFunctions.size();
    }

    public Map<String, FunctionMetadata> getSkippedFunctions() {
        return skippedFunctions;
    }

    public void setSkippedFunctions(Map<String, FunctionMetadata> skippedFunctions) {
        this.skippedFunctions = skippedFunctions;
        this.skippedCount = skippedFunctions.size();
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
     * Error information for a failed function implementation.
     */
    public static class ErrorInfo {
        private String functionName;
        private String error;
        private String sql;

        public ErrorInfo(String functionName, String error, String sql) {
            this.functionName = functionName;
            this.error = error;
            this.sql = sql;
        }

        public String getFunctionName() {
            return functionName;
        }

        public void setFunctionName(String functionName) {
            this.functionName = functionName;
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
        return String.format("StandaloneFunctionImplementationResult{implemented=%d, skipped=%d, errors=%d}",
                implementedCount, skippedCount, errorCount);
    }
}
