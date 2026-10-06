package me.christianrobert.orapgsync.core.job;

import java.util.List;

/**
 * Generic interface for database write jobs that perform create/update/delete operations
 * on Oracle or PostgreSQL databases.
 * This interface extends the base Job interface for write operations that modify database state.
 *
 * @param <T> The type of result data produced by the write operation (e.g., SchemaCreationResult)
 */
public interface DatabaseWriteJob<T> extends Job<T> {

    /**
     * @return The target database type where write operations are performed ("ORACLE" or "POSTGRES")
     */
    String getTargetDatabase();

    /**
     * @return The type of write operation being performed (e.g., "SCHEMA_CREATION", "TABLE_CREATION")
     */
    String getWriteOperationType();

    /**
     * @return The class type of the result being produced
     */
    Class<T> getResultType();

    /**
     * @return A unique identifier combining target database and operation type
     */
    default String getJobTypeIdentifier() {
        return getTargetDatabase() + "_" + getWriteOperationType();
    }
}