package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.TableCreationCompletedEvent;
import me.christianrobert.orapgsync.table.model.TableCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe state manager for table creation results.
 * Maintains the latest table creation results and handles state updates via CDI events.
 */
@ApplicationScoped
public class TableCreationStateManager {

    private static final Logger log = LoggerFactory.getLogger(TableCreationStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private TableCreationResult lastPostgresCreationResult;

    /**
     * Handles table creation completed events and updates state accordingly.
     */
    public void onTableCreationCompleted(@Observes TableCreationCompletedEvent event) {
        lock.writeLock().lock();
        try {
            log.info("Received table creation completed event: {}", event);

            if ("POSTGRES".equals(event.getTargetDatabase())) {
                lastPostgresCreationResult = event.getResult();
                log.debug("Updated PostgreSQL table creation result: created={}, skipped={}, errors={}",
                         event.getCreatedCount(), event.getSkippedCount(), event.getErrorCount());
            } else {
                log.warn("Unknown target database in table creation event: {}", event.getTargetDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the last PostgreSQL table creation result.
     * Returns null if no table creation has been performed yet.
     */
    public TableCreationResult getLastPostgresCreationResult() {
        lock.readLock().lock();
        try {
            return lastPostgresCreationResult;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if any PostgreSQL table creation has been performed.
     */
    public boolean hasPostgresCreationHistory() {
        lock.readLock().lock();
        try {
            return lastPostgresCreationResult != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clear all table creation history (useful for testing or reset operations).
     */
    public void clearCreationHistory() {
        lock.writeLock().lock();
        try {
            lastPostgresCreationResult = null;
            log.info("Cleared all table creation history");
        } finally {
            lock.writeLock().unlock();
        }
    }
}