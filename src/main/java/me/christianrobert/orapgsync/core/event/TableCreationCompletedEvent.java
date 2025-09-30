package me.christianrobert.orapgsync.core.event;

import me.christianrobert.orapgsync.table.model.TableCreationResult;

import java.time.LocalDateTime;

/**
 * Event fired when table creation operations are completed.
 * This event contains the results of the table creation process.
 */
public class TableCreationCompletedEvent {

    private final String targetDatabase;
    private final TableCreationResult result;
    private final LocalDateTime timestamp;

    public TableCreationCompletedEvent(String targetDatabase, TableCreationResult result) {
        this.targetDatabase = targetDatabase;
        this.result = result;
        this.timestamp = LocalDateTime.now();
    }

    public static TableCreationCompletedEvent forPostgres(TableCreationResult result) {
        return new TableCreationCompletedEvent("POSTGRES", result);
    }

    public String getTargetDatabase() {
        return targetDatabase;
    }

    public TableCreationResult getResult() {
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
        return String.format("TableCreationCompletedEvent{targetDatabase='%s', created=%d, skipped=%d, errors=%d, timestamp=%s}",
                           targetDatabase, getCreatedCount(), getSkippedCount(), getErrorCount(), timestamp);
    }
}