package me.christianrobert.orapgsync.core.job.model.typemethod;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the result of type method stub creation in PostgreSQL.
 * Tracks which type methods were created, skipped, or failed.
 */
public class TypeMethodStubCreationResult {
    private List<String> createdMethods;
    private List<String> skippedMethods;
    private Map<String, String> errors; // method name -> error message
    private Map<String, String> failedSqlStatements; // method name -> SQL that failed
    private LocalDateTime executionDateTime;

    public TypeMethodStubCreationResult() {
        this.createdMethods = new ArrayList<>();
        this.skippedMethods = new ArrayList<>();
        this.errors = new HashMap<>();
        this.failedSqlStatements = new HashMap<>();
        this.executionDateTime = LocalDateTime.now();
    }

    public void addCreatedMethod(String methodName) {
        createdMethods.add(methodName);
    }

    public void addSkippedMethod(String methodName) {
        skippedMethods.add(methodName);
    }

    public void addError(String methodName, String errorMessage, String sqlStatement) {
        errors.put(methodName, errorMessage);
        if (sqlStatement != null) {
            failedSqlStatements.put(methodName, sqlStatement);
        }
    }

    public List<String> getCreatedMethods() {
        return createdMethods;
    }

    public List<String> getSkippedMethods() {
        return skippedMethods;
    }

    public Map<String, String> getErrors() {
        return errors;
    }

    public Map<String, String> getFailedSqlStatements() {
        return failedSqlStatements;
    }

    public int getCreatedCount() {
        return createdMethods.size();
    }

    public int getSkippedCount() {
        return skippedMethods.size();
    }

    public int getErrorCount() {
        return errors.size();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public int getTotalProcessed() {
        return createdMethods.size() + skippedMethods.size() + errors.size();
    }

    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    public String getExecutionTimestamp() {
        return executionDateTime.toString();
    }

    public boolean isSuccessful() {
        return errors.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("TypeMethodStubCreationResult{created=%d, skipped=%d, errors=%d}",
                getCreatedCount(), getSkippedCount(), getErrorCount());
    }
}
