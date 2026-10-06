package me.christianrobert.orapgsync.web.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of web gateway project generation.
 */
public class WebGatewayGenerationResult {

    private boolean success;
    private String outputPath;
    private List<String> generatedFiles = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
    private long executionTimeMs;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public List<String> getGeneratedFiles() {
        return generatedFiles;
    }

    public void addGeneratedFile(String file) {
        this.generatedFiles.add(file);
    }

    public List<String> getErrors() {
        return errors;
    }

    public void addError(String error) {
        this.errors.add(error);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public int getFileCount() {
        return generatedFiles.size();
    }

    @Override
    public String toString() {
        return "WebGatewayGenerationResult{" +
                "success=" + success +
                ", outputPath='" + outputPath + '\'' +
                ", fileCount=" + generatedFiles.size() +
                ", errors=" + errors.size() +
                ", executionTimeMs=" + executionTimeMs +
                '}';
    }
}
