package me.christianrobert.orapgsync.core.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Base event for database metadata updates.
 * This event is fired when database metadata is extracted and needs to be stored in state.
 *
 * @param <T> The type of metadata being updated
 */
public abstract class DatabaseMetadataUpdatedEvent<T> {

    private final String sourceDatabase;
    private final String metadataType;
    private final List<T> metadata;
    private final LocalDateTime timestamp;

    protected DatabaseMetadataUpdatedEvent(String sourceDatabase, String metadataType, List<T> metadata) {
        this.sourceDatabase = sourceDatabase;
        this.metadataType = metadataType;
        this.metadata = List.copyOf(metadata); // Defensive copy
        this.timestamp = LocalDateTime.now();
    }

    public String getSourceDatabase() {
        return sourceDatabase;
    }

    public String getMetadataType() {
        return metadataType;
    }

    public List<T> getMetadata() {
        return metadata;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getMetadataCount() {
        return metadata.size();
    }

    @Override
    public String toString() {
        return String.format("%s{sourceDatabase='%s', metadataType='%s', count=%d, timestamp=%s}",
                           getClass().getSimpleName(),
                           sourceDatabase,
                           metadataType,
                           getMetadataCount(),
                           timestamp);
    }
}