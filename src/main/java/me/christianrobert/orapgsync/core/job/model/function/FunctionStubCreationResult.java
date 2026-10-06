package me.christianrobert.orapgsync.core.job.model.function;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL function/procedure stub creation operation.
 * Tracks created function stubs, skipped functions, and any errors that occurred.
 *
 * Function/procedure stubs are created with empty implementations (RETURN NULL or empty body)
 * to support future view migration and PL/SQL code transformation.
 */
public class FunctionStubCreationResult {

    private final List<String> createdFunctions = new ArrayList<>();
    private final List<String> skippedFunctions = new ArrayList<>();
    private final List<FunctionCreationError> errors = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a successfully created function stub.
     * @param qualifiedFunctionName The fully qualified function name
     */
    public void addCreatedFunction(String qualifiedFunctionName) {
        createdFunctions.add(qualifiedFunctionName);
    }

    /**
     * Adds a skipped function (already exists).
     * @param qualifiedFunctionName The fully qualified function name
     */
    public void addSkippedFunction(String qualifiedFunctionName) {
        skippedFunctions.add(qualifiedFunctionName);
    }

    /**
     * Adds an error that occurred during function creation.
     * @param qualifiedFunctionName The fully qualified function name that failed
     * @param errorMessage The error message
     * @param sqlStatement The SQL statement that failed
     */
    public void addError(String qualifiedFunctionName, String errorMessage, String sqlStatement) {
        errors.add(new FunctionCreationError(qualifiedFunctionName, errorMessage, sqlStatement));
    }

    /**
     * Gets the list of successfully created functions.
     * @return List of qualified function names
     */
    public List<String> getCreatedFunctions() {
        return new ArrayList<>(createdFunctions);
    }

    /**
     * Gets the list of skipped functions.
     * @return List of qualified function names
     */
    public List<String> getSkippedFunctions() {
        return new ArrayList<>(skippedFunctions);
    }

    /**
     * Gets the list of errors that occurred.
     * @return List of FunctionCreationError objects
     */
    public List<FunctionCreationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Gets the number of successfully created functions.
     */
    public int getCreatedCount() {
        return createdFunctions.size();
    }

    /**
     * Gets the number of skipped functions.
     */
    public int getSkippedCount() {
        return skippedFunctions.size();
    }

    /**
     * Gets the number of errors.
     */
    public int getErrorCount() {
        return errors.size();
    }

    /**
     * Gets the total number of functions processed.
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
     * Error information for a failed function creation.
     */
    public static class FunctionCreationError {
        private final String functionName;
        private final String errorMessage;
        private final String sqlStatement;

        public FunctionCreationError(String functionName, String errorMessage, String sqlStatement) {
            this.functionName = functionName;
            this.errorMessage = errorMessage;
            this.sqlStatement = sqlStatement;
        }

        public String getFunctionName() {
            return functionName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlStatement() {
            return sqlStatement;
        }

        @Override
        public String toString() {
            return String.format("FunctionCreationError{functionName='%s', error='%s', sql='%s'}",
                    functionName, errorMessage, sqlStatement);
        }
    }

    @Override
    public String toString() {
        return String.format("FunctionStubCreationResult{created=%d, skipped=%d, errors=%d, successful=%s}",
                getCreatedCount(), getSkippedCount(), getErrorCount(), isSuccessful());
    }
}
