package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.ObjectDataTypeUpdatedEvent;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages object data type metadata state for both Oracle and PostgreSQL databases.
 * Listens to ObjectDataTypeUpdatedEvent and maintains the current state.
 */
@ApplicationScoped
public class ObjectDataTypeStateManager {

    private static final Logger log = LoggerFactory.getLogger(ObjectDataTypeStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Separate storage for Oracle and PostgreSQL
    private List<ObjectDataTypeMetaData> oracleObjectDataTypes = new ArrayList<>();
    private List<ObjectDataTypeMetaData> postgresObjectDataTypes = new ArrayList<>();

    /**
     * Event handler for object data type updates.
     */
    public void onObjectDataTypeUpdated(@Observes ObjectDataTypeUpdatedEvent event) {
        log.info("Received object data type update event: {}", event);

        lock.writeLock().lock();
        try {
            if ("ORACLE".equals(event.getSourceDatabase())) {
                this.oracleObjectDataTypes = new ArrayList<>(event.getMetadata());
                log.info("Updated Oracle object data types: {} types", event.getMetadataCount());
            } else if ("POSTGRES".equals(event.getSourceDatabase())) {
                this.postgresObjectDataTypes = new ArrayList<>(event.getMetadata());
                log.info("Updated PostgreSQL object data types: {} types", event.getMetadataCount());
            } else {
                log.warn("Unknown source database in object data type event: {}", event.getSourceDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets Oracle object data type metadata.
     * @return Defensive copy of Oracle object data type metadata
     */
    public List<ObjectDataTypeMetaData> getOracleObjectDataTypeMetaData() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(oracleObjectDataTypes);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets PostgreSQL object data type metadata.
     * @return Defensive copy of PostgreSQL object data type metadata
     */
    public List<ObjectDataTypeMetaData> getPostgresObjectDataTypeMetaData() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(postgresObjectDataTypes);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets object data type metadata for the specified database.
     * @param sourceDatabase "ORACLE" or "POSTGRES"
     * @return Defensive copy of object data type metadata for the specified database
     */
    public List<ObjectDataTypeMetaData> getObjectDataTypeMetaData(String sourceDatabase) {
        if ("ORACLE".equals(sourceDatabase)) {
            return getOracleObjectDataTypeMetaData();
        } else if ("POSTGRES".equals(sourceDatabase)) {
            return getPostgresObjectDataTypeMetaData();
        } else {
            log.warn("Unknown source database requested: {}", sourceDatabase);
            return new ArrayList<>();
        }
    }

    /**
     * Gets the count of object data types for a specific database.
     */
    public int getObjectDataTypeCount(String sourceDatabase) {
        return getObjectDataTypeMetaData(sourceDatabase).size();
    }

    /**
     * Checks if object data type metadata is available for the specified database.
     */
    public boolean hasObjectDataTypeMetadata(String sourceDatabase) {
        return getObjectDataTypeCount(sourceDatabase) > 0;
    }
}