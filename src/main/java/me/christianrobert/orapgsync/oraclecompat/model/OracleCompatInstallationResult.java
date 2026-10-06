package me.christianrobert.orapgsync.oraclecompat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result object for Oracle compatibility layer installation.
 * <p>
 * Tracks which functions were installed successfully at each support level,
 * which were skipped, and which failed with errors.
 */
public class OracleCompatInstallationResult {
    private final List<String> installedFull = new ArrayList<>();
    private final List<String> installedPartial = new ArrayList<>();
    private final List<String> installedStubs = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private final List<String> errorMessages = new ArrayList<>();

    private int totalFunctions;
    private long executionTimeMs;

    // Add methods

    public void addInstalled(String functionName, SupportLevel level) {
        switch (level) {
            case FULL -> installedFull.add(functionName);
            case PARTIAL -> installedPartial.add(functionName);
            case STUB -> installedStubs.add(functionName);
            case NONE -> skipped.add(functionName);
        }
    }

    public void addFailed(String functionName, String error) {
        failed.add(functionName);
        errorMessages.add(functionName + ": " + error);
    }

    public void addSkipped(String functionName) {
        skipped.add(functionName);
    }

    // Summary methods

    public int getTotalInstalled() {
        return installedFull.size() + installedPartial.size() + installedStubs.size();
    }

    public boolean hasErrors() {
        return !failed.isEmpty();
    }

    // Getters and setters

    public List<String> getInstalledFull() {
        return installedFull;
    }

    public List<String> getInstalledPartial() {
        return installedPartial;
    }

    public List<String> getInstalledStubs() {
        return installedStubs;
    }

    public List<String> getSkipped() {
        return skipped;
    }

    public List<String> getFailed() {
        return failed;
    }

    public List<String> getErrorMessages() {
        return errorMessages;
    }

    public int getTotalFunctions() {
        return totalFunctions;
    }

    public void setTotalFunctions(int totalFunctions) {
        this.totalFunctions = totalFunctions;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    @Override
    public String toString() {
        return "OracleCompatInstallationResult{" +
                "totalFunctions=" + totalFunctions +
                ", installed=" + getTotalInstalled() +
                " (full=" + installedFull.size() +
                ", partial=" + installedPartial.size() +
                ", stubs=" + installedStubs.size() +
                "), failed=" + failed.size() +
                ", executionTimeMs=" + executionTimeMs +
                '}';
    }
}
