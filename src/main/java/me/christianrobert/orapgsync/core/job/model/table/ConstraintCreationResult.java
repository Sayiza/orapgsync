package me.christianrobert.orapgsync.core.job.model.table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL constraint creation operation.
 * Tracks created constraints, skipped constraints, and any errors that occurred.
 *
 * Constraints are created in dependency order:
 * 1. Primary Keys (foundational)
 * 2. Unique Constraints (can be referenced by FKs)
 * 3. Foreign Keys (depend on target PKs/Unique)
 * 4. Check Constraints (independent)
 */
public class ConstraintCreationResult {

    private final List<ConstraintInfo> createdConstraints = new ArrayList<>();
    private final List<ConstraintInfo> skippedConstraints = new ArrayList<>();
    private final List<ConstraintCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created constraint.
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     * @param constraintName The constraint name
     * @param constraintType The constraint type (P, R, U, C)
     */
    public void addCreatedConstraint(String qualifiedTableName, String constraintName, String constraintType) {
        createdConstraints.add(new ConstraintInfo(qualifiedTableName, constraintName, constraintType));
    }

    /**
     * Adds a skipped constraint (already exists).
     * @param qualifiedTableName The fully qualified table name (schema.tablename)
     * @param constraintName The constraint name
     * @param constraintType The constraint type (P, R, U, C)
     * @param reason The reason for skipping
     */
    public void addSkippedConstraint(String qualifiedTableName, String constraintName, String constraintType, String reason) {
        skippedConstraints.add(new ConstraintInfo(qualifiedTableName, constraintName, constraintType, reason));
    }

    /**
     * Adds an error that occurred during constraint creation.
     * @param qualifiedTableName The fully qualified table name that failed
     * @param constraintName The constraint name
     * @param constraintType The constraint type (P, R, U, C)
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedTableName, String constraintName, String constraintType,
                        String errorMessage, String sqlStatement) {
        errors.add(new ConstraintCreationError(qualifiedTableName, constraintName, constraintType,
                                               errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created constraints.
     * @return List of ConstraintInfo objects
     */
    public List<ConstraintInfo> getCreatedConstraints() {
        return new ArrayList<>(createdConstraints);
    }

    /**
     * Gets the list of skipped constraints.
     * @return List of ConstraintInfo objects
     */
    public List<ConstraintInfo> getSkippedConstraints() {
        return new ArrayList<>(skippedConstraints);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of ConstraintCreationError objects
     */
    public List<ConstraintCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created constraints.
     */
    public int getCreatedCount() {
        return createdConstraints.size();
    }

    /**
     * Gets the number of skipped constraints.
     */
    public int getSkippedCount() {
        return skippedConstraints.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of constraints processed.
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
     * Information about a constraint (created or skipped).
     */
    public static class ConstraintInfo {
        private final String tableName;
        private final String constraintName;
        private final String constraintType;
        private final String reason; // For skipped constraints

        public ConstraintInfo(String tableName, String constraintName, String constraintType) {
            this(tableName, constraintName, constraintType, null);
        }

        public ConstraintInfo(String tableName, String constraintName, String constraintType, String reason) {
            this.tableName = tableName;
            this.constraintName = constraintName;
            this.constraintType = constraintType;
            this.reason = reason;
        }

        public String getTableName() {
            return tableName;
        }

        public String getConstraintName() {
            return constraintName;
        }

        public String getConstraintType() {
            return constraintType;
        }

        public String getReason() {
            return reason;
        }

        public String getConstraintTypeName() {
            return switch (constraintType) {
                case "P" -> "PRIMARY KEY";
                case "R" -> "FOREIGN KEY";
                case "U" -> "UNIQUE";
                case "C" -> "CHECK";
                default -> "UNKNOWN";
            };
        }

        @Override
        public String toString() {
            return String.format("ConstraintInfo{table='%s', name='%s', type='%s'}",
                    tableName, constraintName, constraintType);
        }
    }

    /**
     * Error information for a failed constraint creation.
     */
    public static class ConstraintCreationError {
        private final String tableName;
        private final String constraintName;
        private final String constraintType;
        private final String errorMessage;
        private final String sqlStatement;

        public ConstraintCreationError(String tableName, String constraintName, String constraintType,
                                       String errorMessage, String sqlStatement) {
            this.tableName = tableName;
            this.constraintName = constraintName;
            this.constraintType = constraintType;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getTableName() {
            return tableName;
        }

        public String getConstraintName() {
            return constraintName;
        }

        public String getConstraintType() {
            return constraintType;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        public String getConstraintTypeName() {
            return switch (constraintType) {
                case "P" -> "PRIMARY KEY";
                case "R" -> "FOREIGN KEY";
                case "U" -> "UNIQUE";
                case "C" -> "CHECK";
                default -> "UNKNOWN";
            };
        }

        @Override
        public String toString() {
            return String.format("ConstraintCreationError{table='%s', constraint='%s', type='%s', error='%s', sql='%s'}",
                    tableName, constraintName, constraintType, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("ConstraintCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
