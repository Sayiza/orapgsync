package me.christianrobert.orapgsync.core.job;

import java.util.List;

/**
 * Generic interface for database extraction jobs that extract metadata from Oracle or PostgreSQL.
 * This interface extends the base Job interface and provides additional metadata about the extraction.
 *
 * @param <T> The type of metadata being extracted (e.g., TableMetadata, ObjectDataTypeMetaData)
 */
public interface DatabaseExtractionJob<T> extends Job<List<T>> {

    /**
     * @return The source database type ("ORACLE" or "POSTGRES")
     */
    String getSourceDatabase();

    /**
     * @return The type of extraction being performed (e.g., "TABLE_METADATA", "OBJECT_DATATYPE", "ROW_COUNT")
     */
    String getExtractionType();

    /**
     * @return The class type of the metadata being extracted
     */
    Class<T> getResultType();

    /**
     * @return A unique identifier combining source database and extraction type
     */
    default String getJobTypeIdentifier() {
        return getSourceDatabase() + "_" + getExtractionType();
    }
}