package me.christianrobert.orapgsync.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import me.christianrobert.orapgsync.core.event.ObjectTypeCreationCompletedEvent;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectTypeCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe state manager for object type creation results.
 * Maintains the latest object type creation results and handles state updates via CDI events.
 */
@ApplicationScoped
public class ObjectTypeCreationStateManager {

    private static final Logger log = LoggerFactory.getLogger(ObjectTypeCreationStateManager.class);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private ObjectTypeCreationResult lastPostgresCreationResult;

    /**
     * Handles object type creation completed events and updates state accordingly.
     */
    public void onObjectTypeCreationCompleted(@Observes ObjectTypeCreationCompletedEvent event) {
        lock.writeLock().lock();
        try {
            log.info("Received object type creation completed event: {}", event);

            if ("POSTGRES".equals(event.getTargetDatabase())) {
                lastPostgresCreationResult = event.getResult();
                log.debug("Updated PostgreSQL object type creation result: created={}, skipped={}, errors={}",
                         event.getCreatedCount(), event.getSkippedCount(), event.getErrorCount());
            } else {
                log.warn("Unknown target database in object type creation event: {}", event.getTargetDatabase());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the last PostgreSQL object type creation result.
     * Returns null if no object type creation has been performed yet.
     */
    public ObjectTypeCreationResult getLastPostgresCreationResult() {
        lock.readLock().lock();
        try {
            return lastPostgresCreationResult;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if any PostgreSQL object type creation has been performed.
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
     * Clear all object type creation history (useful for testing or reset operations).
     */
    public void clearCreationHistory() {
        lock.writeLock().lock();
        try {
            lastPostgresCreationResult = null;
            log.info("Cleared all object type creation history");
        } finally {
            lock.writeLock().unlock();
        }
    }
}