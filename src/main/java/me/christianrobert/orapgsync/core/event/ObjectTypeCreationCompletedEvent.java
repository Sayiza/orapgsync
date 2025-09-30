package me.christianrobert.orapgsync.core.event;

import me.christianrobert.orapgsync.objectdatatype.model.ObjectTypeCreationResult;

import java.time.LocalDateTime;

/**
 * Event fired when object type creation operations are completed.
 * This event contains the results of the object type creation process.
 */
public class ObjectTypeCreationCompletedEvent {

    private final String targetDatabase;
    private final ObjectTypeCreationResult result;
    private final LocalDateTime timestamp;

    public ObjectTypeCreationCompletedEvent(String targetDatabase, ObjectTypeCreationResult result) {
        this.targetDatabase = targetDatabase;
        this.result = result;
        this.timestamp = LocalDateTime.now();
    }

    public static ObjectTypeCreationCompletedEvent forPostgres(ObjectTypeCreationResult result) {
        return new ObjectTypeCreationCompletedEvent("POSTGRES", result);
    }

    public String getTargetDatabase() {
        return targetDatabase;
    }

    public ObjectTypeCreationResult getResult() {
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
        return String.format("ObjectTypeCreationCompletedEvent{targetDatabase='%s', created=%d, skipped=%d, errors=%d, timestamp=%s}",
                           targetDatabase, getCreatedCount(), getSkippedCount(), getErrorCount(), timestamp);
    }
}