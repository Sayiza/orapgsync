package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.RowCountUpdatedEvent;
import me.christianrobert.orapgsync.rowcount.model.RowCountMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages row count metadata state for both Oracle and PostgreSQL databases.
 * Listens to RowCountUpdatedEvent and maintains the current state.
 */
@ApplicationScoped
public class RowCountStateManager {

    private static final Logger log = LoggerFactory.getLogger(RowCountStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Separate storage for Oracle and PostgreSQL
    private List<RowCountMetadata> oracleRowCounts = new ArrayList<>();
    private List<RowCountMetadata> postgresRowCounts = new ArrayList<>();

    /**
     * Event handler for row count updates.
     */
    public void onRowCountUpdated(@Observes RowCountUpdatedEvent event) {
        log.info("Received row count update event: {}", event);

        lock.writeLock().lock();
        try {
            if ("ORACLE".equals(event.getSourceDatabase())) {
                this.oracleRowCounts = new ArrayList<>(event.getMetadata());
                log.info("Updated Oracle row counts: {} tables", event.getMetadataCount());
            } else if ("POSTGRES".equals(event.getSourceDatabase())) {
                this.postgresRowCounts = new ArrayList<>(event.getMetadata());
                log.info("Updated PostgreSQL row counts: {} tables", event.getMetadataCount());
            } else {
                log.warn("Unknown source database in row count event: {}", event.getSourceDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets Oracle row count metadata.
     * @return Defensive copy of Oracle row count metadata
     */
    public List<RowCountMetadata> getOracleRowCounts() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(oracleRowCounts);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets PostgreSQL row count metadata.
     * @return Defensive copy of PostgreSQL row count metadata
     */
    public List<RowCountMetadata> getPostgresRowCounts() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(postgresRowCounts);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets row count metadata for the specified database.
     * @param sourceDatabase "ORACLE" or "POSTGRES"
     * @return Defensive copy of row count metadata for the specified database
     */
    public List<RowCountMetadata> getRowCounts(String sourceDatabase) {
        if ("ORACLE".equals(sourceDatabase)) {
            return getOracleRowCounts();
        } else if ("POSTGRES".equals(sourceDatabase)) {
            return getPostgresRowCounts();
        } else {
            log.warn("Unknown source database requested: {}", sourceDatabase);
            return new ArrayList<>();
        }
    }

    /**
     * Gets the count of tables with row count data for a specific database.
     */
    public int getRowCountDataCount(String sourceDatabase) {
        return getRowCounts(sourceDatabase).size();
    }

    /**
     * Checks if row count data is available for the specified database.
     */
    public boolean hasRowCountData(String sourceDatabase) {
        return getRowCountDataCount(sourceDatabase) > 0;
    }

    /**
     * Gets total row count for all tables in the specified database.
     */
    public long getTotalRowCount(String sourceDatabase) {
        return getRowCounts(sourceDatabase).stream()
                .mapToLong(RowCountMetadata::getRowCount)
                .sum();
    }
}