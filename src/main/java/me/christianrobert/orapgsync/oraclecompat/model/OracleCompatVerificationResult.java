package me.christianrobert.orapgsync.oraclecompat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result object for Oracle compatibility layer verification.
 * <p>
 * Tracks how many functions were verified, how many are missing,
 * and any errors encountered during verification.
 */
public class OracleCompatVerificationResult {
    private int verified;
    private int missing;
    private final List<String> errors = new ArrayList<>();
    private int totalExpected;
    private long executionTimeMs;

    // Add methods

    public void addError(String error) {
        errors.add(error);
    }

    // Getters and setters

    public int getVerified() {
        return verified;
    }

    public void setVerified(int verified) {
        this.verified = verified;
    }

    public int getMissing() {
        return missing;
    }

    public void setMissing(int missing) {
        this.missing = missing;
    }

    public List<String> getErrors() {
        return errors;
    }

    public int getTotalExpected() {
        return totalExpected;
    }

    public void setTotalExpected(int totalExpected) {
        this.totalExpected = totalExpected;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    @Override
    public String toString() {
        return "OracleCompatVerificationResult{" +
                "verified=" + verified +
                ", missing=" + missing +
                ", errors=" + errors.size() +
                ", totalExpected=" + totalExpected +
                ", executionTimeMs=" + executionTimeMs +
                '}';
    }
}
