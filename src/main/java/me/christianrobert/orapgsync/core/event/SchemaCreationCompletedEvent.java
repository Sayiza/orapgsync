package me.christianrobert.orapgsync.core.event;

import me.christianrobert.orapgsync.schema.model.SchemaCreationResult;

import java.time.LocalDateTime;

/**
 * Event fired when schema creation operations are completed.
 * This event contains the results of the schema creation process.
 */
public class SchemaCreationCompletedEvent {

    private final String targetDatabase;
    private final SchemaCreationResult result;
    private final LocalDateTime timestamp;

    public SchemaCreationCompletedEvent(String targetDatabase, SchemaCreationResult result) {
        this.targetDatabase = targetDatabase;
        this.result = result;
        this.timestamp = LocalDateTime.now();
    }

    public static SchemaCreationCompletedEvent forPostgres(SchemaCreationResult result) {
        return new SchemaCreationCompletedEvent("POSTGRES", result);
    }

    public String getTargetDatabase() {
        return targetDatabase;
    }

    public SchemaCreationResult getResult() {
        return result;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getCreatedCount() {
        return result.getCreatedCount();
    }

    public int getSkippedCount() {
        return result.getSkippedCount();
    }

    public int getErrorCount() {
        return result.getErrorCount();
    }

    public boolean isSuccessful() {
        return result.isSuccessful();
    }

    @Override
    public String toString() {
        return String.format("SchemaCreationCompletedEvent{targetDatabase='%s', created=%d, skipped=%d, errors=%d, timestamp=%s}",
                           targetDatabase, getCreatedCount(), getSkippedCount(), getErrorCount(), timestamp);
    }
}