package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.TableMetadataUpdatedEvent;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages table metadata state for both Oracle and PostgreSQL databases.
 * Listens to TableMetadataUpdatedEvent and maintains the current state.
 */
@ApplicationScoped
public class TableMetadataStateManager {

    private static final Logger log = LoggerFactory.getLogger(TableMetadataStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Separate storage for Oracle and PostgreSQL
    private List<TableMetadata> oracleTableMetadata = new ArrayList<>();
    private List<TableMetadata> postgresTableMetadata = new ArrayList<>();

    /**
     * Event handler for table metadata updates.
     */
    public void onTableMetadataUpdated(@Observes TableMetadataUpdatedEvent event) {
        log.info("Received table metadata update event: {}", event);

        lock.writeLock().lock();
        try {
            if ("ORACLE".equals(event.getSourceDatabase())) {
                this.oracleTableMetadata = new ArrayList<>(event.getMetadata());
                log.info("Updated Oracle table metadata: {} tables", event.getMetadataCount());
            } else if ("POSTGRES".equals(event.getSourceDatabase())) {
                this.postgresTableMetadata = new ArrayList<>(event.getMetadata());
                log.info("Updated PostgreSQL table metadata: {} tables", event.getMetadataCount());
            } else {
                log.warn("Unknown source database in table metadata event: {}", event.getSourceDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets Oracle table metadata.
     * @return Defensive copy of Oracle table metadata
     */
    public List<TableMetadata> getOracleTableMetadata() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(oracleTableMetadata);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets PostgreSQL table metadata.
     * @return Defensive copy of PostgreSQL table metadata
     */
    public List<TableMetadata> getPostgresTableMetadata() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(postgresTableMetadata);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets table metadata for the specified database.
     * @param sourceDatabase "ORACLE" or "POSTGRES"
     * @return Defensive copy of table metadata for the specified database
     */
    public List<TableMetadata> getTableMetadata(String sourceDatabase) {
        if ("ORACLE".equals(sourceDatabase)) {
            return getOracleTableMetadata();
        } else if ("POSTGRES".equals(sourceDatabase)) {
            return getPostgresTableMetadata();
        } else {
            log.warn("Unknown source database requested: {}", sourceDatabase);
            return new ArrayList<>();
        }
    }

    /**
     * Gets the count of tables for a specific database.
     */
    public int getTableCount(String sourceDatabase) {
        return getTableMetadata(sourceDatabase).size();
    }

    /**
     * Checks if table metadata is available for the specified database.
     */
    public boolean hasTableMetadata(String sourceDatabase) {
        return getTableCount(sourceDatabase) > 0;
    }
}