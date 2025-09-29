package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.SchemaListUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages schema list state for both Oracle and PostgreSQL databases.
 * Listens to SchemaListUpdatedEvent and maintains the current state.
 */
@ApplicationScoped
public class SchemaStateManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Separate storage for Oracle and PostgreSQL
    private List<String> oracleSchemaNames = new ArrayList<>();
    private List<String> postgresSchemaNames = new ArrayList<>();

    /**
     * Event handler for schema list updates.
     */
    public void onSchemaListUpdated(@Observes SchemaListUpdatedEvent event) {
        log.info("Received schema list update event: {}", event);

        lock.writeLock().lock();
        try {
            if ("ORACLE".equals(event.getSourceDatabase())) {
                this.oracleSchemaNames = new ArrayList<>(event.getMetadata());
                log.info("Updated Oracle schema list: {} schemas", event.getMetadataCount());
            } else if ("POSTGRES".equals(event.getSourceDatabase())) {
                this.postgresSchemaNames = new ArrayList<>(event.getMetadata());
                log.info("Updated PostgreSQL schema list: {} schemas", event.getMetadataCount());
            } else {
                log.warn("Unknown source database in schema list event: {}", event.getSourceDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets Oracle schema names.
     * @return Defensive copy of Oracle schema names
     */
    public List<String> getOracleSchemaNames() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(oracleSchemaNames);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets PostgreSQL schema names.
     * @return Defensive copy of PostgreSQL schema names
     */
    public List<String> getPostgresSchemaNames() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(postgresSchemaNames);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets schema names for the specified database.
     * @param sourceDatabase "ORACLE" or "POSTGRES"
     * @return Defensive copy of schema names for the specified database
     */
    public List<String> getSchemaNames(String sourceDatabase) {
        if ("ORACLE".equals(sourceDatabase)) {
            return getOracleSchemaNames();
        } else if ("POSTGRES".equals(sourceDatabase)) {
            return getPostgresSchemaNames();
        } else {
            log.warn("Unknown source database requested: {}", sourceDatabase);
            return new ArrayList<>();
        }
    }

    /**
     * Gets the count of schemas for a specific database.
     */
    public int getSchemaCount(String sourceDatabase) {
        return getSchemaNames(sourceDatabase).size();
    }

    /**
     * Checks if schema information is available for the specified database.
     */
    public boolean hasSchemaInformation(String sourceDatabase) {
        return getSchemaCount(sourceDatabase) > 0;
    }
}