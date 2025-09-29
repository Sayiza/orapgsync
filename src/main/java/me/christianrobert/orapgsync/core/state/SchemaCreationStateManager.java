package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.SchemaCreationCompletedEvent;
import me.christianrobert.orapgsync.schema.model.SchemaCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe state manager for schema creation results.
 * Maintains the latest schema creation results and handles state updates via CDI events.
 */
@ApplicationScoped
public class SchemaCreationStateManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaCreationStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private SchemaCreationResult lastPostgresCreationResult;

    /**
     * Handles schema creation completed events and updates state accordingly.
     */
    public void onSchemaCreationCompleted(@Observes SchemaCreationCompletedEvent event) {
        lock.writeLock().lock();
        try {
            log.info("Received schema creation completed event: {}", event);

            if ("POSTGRES".equals(event.getTargetDatabase())) {
                lastPostgresCreationResult = event.getResult();
                log.debug("Updated PostgreSQL schema creation result: created={}, skipped={}, errors={}",
                         event.getCreatedCount(), event.getSkippedCount(), event.getErrorCount());
            } else {
                log.warn("Unknown target database in schema creation event: {}", event.getTargetDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the last PostgreSQL schema creation result.
     * Returns null if no schema creation has been performed yet.
     */
    public SchemaCreationResult getLastPostgresCreationResult() {
        lock.readLock().lock();
        try {
            return lastPostgresCreationResult;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if any PostgreSQL schema creation has been performed.
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
     * Clear all schema creation history (useful for testing or reset operations).
     */
    public void clearCreationHistory() {
        lock.writeLock().lock();
        try {
            lastPostgresCreationResult = null;
            log.info("Cleared all schema creation history");
        } finally {
            lock.writeLock().unlock();
        }
    }
}