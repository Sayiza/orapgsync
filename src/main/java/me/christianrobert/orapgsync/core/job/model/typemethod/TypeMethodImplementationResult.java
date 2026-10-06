package me.christianrobert.orapgsync.core.job.model.typemethod;

import java.util.HashMap;
import java.util.Map;

/**
 * Result object for type method implementation operations.
 * Tracks which type methods were successfully implemented, skipped, or failed.
 * This is used for Phase 2 implementation (replacing stubs with actual PL/pgSQL logic).
 */
public class TypeMethodImplementationResult {
    private Map<String, TypeMethodMetadata> implementedTypeMethods;
    private Map<String, TypeMethodMetadata> skippedTypeMethods;
    private Map<String, ErrorInfo> errors;
    private int implementedCount;
    private int skippedCount;
    private int errorCount;

    public TypeMethodImplementationResult() {
        this.implementedTypeMethods = new HashMap<>();
        this.skippedTypeMethods = new HashMap<>();
        this.errors = new HashMap<>();
        this.implementedCount = 0;
        this.skippedCount = 0;
        this.errorCount = 0;
    }

    public void addImplementedTypeMethod(TypeMethodMetadata typeMethod) {
        String key = typeMethod.getQualifiedName();
        implementedTypeMethods.put(key, typeMethod);
        implementedCount++;
    }

    public void addSkippedTypeMethod(TypeMethodMetadata typeMethod) {
        String key = typeMethod.getQualifiedName();
        skippedTypeMethods.put(key, typeMethod);
        skippedCount++;
    }

    public void addError(String typeMethodName, String errorMessage, String sql) {
        ErrorInfo error = new ErrorInfo(typeMethodName, errorMessage, sql);
        errors.put(typeMethodName, error);
        errorCount++;
    }

    // Getters and setters
    public Map<String, TypeMethodMetadata> getImplementedTypeMethods() {
        return implementedTypeMethods;
    }

    public void setImplementedTypeMethods(Map<String, TypeMethodMetadata> implementedTypeMethods) {
        this.implementedTypeMethods = implementedTypeMethods;
        this.implementedCount = implementedTypeMethods.size();
    }

    public Map<String, TypeMethodMetadata> getSkippedTypeMethods() {
        return skippedTypeMethods;
    }

    public void setSkippedTypeMethods(Map<String, TypeMethodMetadata> skippedTypeMethods) {
        this.skippedTypeMethods = skippedTypeMethods;
        this.skippedCount = skippedTypeMethods.size();
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
     * Error information for a failed type method implementation.
     */
    public static class ErrorInfo {
        private String typeMethodName;
        private String error;
        private String sql;

        public ErrorInfo(String typeMethodName, String error, String sql) {
            this.typeMethodName = typeMethodName;
            this.error = error;
            this.sql = sql;
        }

        public String getTypeMethodName() {
            return typeMethodName;
        }

        public void setTypeMethodName(String typeMethodName) {
            this.typeMethodName = typeMethodName;
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
        return String.format("TypeMethodImplementationResult{implemented=%d, skipped=%d, errors=%d}",
                implementedCount, skippedCount, errorCount);
    }
}
