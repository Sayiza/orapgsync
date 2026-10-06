package me.christianrobert.orapgsync.core.job.model;

import java.time.LocalDateTime;
import java.util.Map;

public class JobProgress {
    private int percentage;
    private String currentTask;
    private String details;
    private LocalDateTime lastUpdated;
    private Map<String, Object> metadata;

    public JobProgress() {
        this.percentage = 0;
        this.currentTask = "";
        this.details = "";
        this.lastUpdated = LocalDateTime.now();
    }

    public JobProgress(int percentage, String currentTask) {
        this();
        this.percentage = Math.max(0, Math.min(100, percentage));
        this.currentTask = currentTask != null ? currentTask : "";
        this.lastUpdated = LocalDateTime.now();
    }

    public JobProgress(int percentage, String currentTask, String details) {
        this(percentage, currentTask);
        this.details = details != null ? details : "";
    }

    public int getPercentage() {
        return percentage;
    }

    public void setPercentage(int percentage) {
        this.percentage = Math.max(0, Math.min(100, percentage));
        this.lastUpdated = LocalDateTime.now();
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(String currentTask) {
        this.currentTask = currentTask != null ? currentTask : "";
        this.lastUpdated = LocalDateTime.now();
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details != null ? details : "";
        this.lastUpdated = LocalDateTime.now();
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
        this.lastUpdated = LocalDateTime.now();
    }

    public void updateProgress(int percentage, String currentTask) {
        setPercentage(percentage);
        setCurrentTask(currentTask);
    }

    public void updateProgress(int percentage, String currentTask, String details) {
        updateProgress(percentage, currentTask);
        setDetails(details);
    }

    @Override
    public String toString() {
        return "JobProgress{" +
                "percentage=" + percentage +
                ", currentTask='" + currentTask + '\'' +
                ", details='" + details + '\'' +
                ", lastUpdated=" + lastUpdated +
                '}';
    }
}