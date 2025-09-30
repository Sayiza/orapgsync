package me.christianrobert.orapgsync.objectdatatype.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL object type creation operation.
 * Tracks created types, skipped types, and any errors that occurred.
 */
public class ObjectTypeCreationResult {

    private final List<String> createdTypes = new ArrayList<>();
    private final List<String> skippedTypes = new ArrayList<>();
    private final List<ObjectTypeCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created object type.
     * @param qualifiedTypeName The fully qualified type name (schema.typename)
     */
    public void addCreatedType(String qualifiedTypeName) {
        createdTypes.add(qualifiedTypeName);
    }

    /**
     * Adds a skipped object type (already exists).
     * @param qualifiedTypeName The fully qualified type name (schema.typename)
     */
    public void addSkippedType(String qualifiedTypeName) {
        skippedTypes.add(qualifiedTypeName);
    }

    /**
     * Adds an error that occurred during type creation.
     * @param qualifiedTypeName The fully qualified type name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedTypeName, String errorMessage, String sqlStatement) {
        errors.add(new ObjectTypeCreationError(qualifiedTypeName, errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created object types.
     * @return List of qualified type names
     */
    public List<String> getCreatedTypes() {
        return new ArrayList<>(createdTypes);
    }

    /**
     * Gets the list of skipped object types.
     * @return List of qualified type names
     */
    public List<String> getSkippedTypes() {
        return new ArrayList<>(skippedTypes);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of ObjectTypeCreationError objects
     */
    public List<ObjectTypeCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created types.
     */
    public int getCreatedCount() {
        return createdTypes.size();
    }

    /**
     * Gets the number of skipped types.
     */
    public int getSkippedCount() {
        return skippedTypes.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of types processed.
     */
    public int getTotalProcessed() {
        return getCreatedCount() + getSkippedCount() + getErrorCount();
    }

    /**
     * Checks if the operation was successful (no errors).
     */
    public boolean isSuccessful() {
        return errors.isEmpty();
    }

    /**
     * Checks if there were any errors.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * Gets the execution date and time.
     */
    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    /**
     * Gets the execution timestamp for compatibility.
     */
    public LocalDateTime getExecutionTimestamp() {
        return executionDateTime;
    }

    /**
     * Error information for a failed object type creation.
     */
    public static class ObjectTypeCreationError {
        private final String typeName;
        private final String errorMessage;
        private final String sqlStatement;

        public ObjectTypeCreationError(String typeName, String errorMessage, String sqlStatement) {
            this.typeName = typeName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getTypeName() {
            return typeName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("ObjectTypeCreationError{typeName='%s', error='%s', sql='%s'}",
                    typeName, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("ObjectTypeCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}