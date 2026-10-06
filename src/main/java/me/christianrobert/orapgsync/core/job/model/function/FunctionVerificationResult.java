package me.christianrobert.orapgsync.core.job.model.function;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Result of unified PostgreSQL function verification operation.
 * Verifies all functions (both stubs and implementations) and returns their DDL for manual inspection.
 *
 * This verification:
 * - Queries PostgreSQL directly (no state dependency)
 * - Extracts function DDL using pg_get_functiondef()
 * - Auto-detects status (STUB vs IMPLEMENTED) from function body content
 * - Groups results by schema for easy navigation
 * - Handles both standalone and package functions (with __ naming)
 */
public class FunctionVerificationResult {

    private final Map<String, List<FunctionInfo>> functionsBySchema = new LinkedHashMap<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    /**
     * Adds a function to the verification result.
     */
    public void addFunction(String schema, FunctionInfo functionInfo) {
        functionsBySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(functionInfo);
    }

    /**
     * Gets all functions grouped by schema.
     */
    public Map<String, List<FunctionInfo>> getFunctionsBySchema() {
        return new LinkedHashMap<>(functionsBySchema);
    }

    /**
     * Gets the list of all schemas.
     */
    public List<String> getSchemas() {
        return new ArrayList<>(functionsBySchema.keySet());
    }

    /**
     * Gets functions for a specific schema.
     */
    public List<FunctionInfo> getFunctionsForSchema(String schema) {
        return functionsBySchema.getOrDefault(schema, new ArrayList<>());
    }

    /**
     * Gets the total number of functions across all schemas.
     */
    public int getTotalFunctions() {
        return functionsBySchema.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    /**
     * Gets the total number of stub functions.
     */
    public int getStubCount() {
        return (int) functionsBySchema.values().stream()
                .flatMap(List::stream)
                .filter(f -> f.getStatus() == FunctionStatus.STUB)
                .count();
    }

    /**
     * Gets the total number of implemented functions.
     */
    public int getImplementedCount() {
        return (int) functionsBySchema.values().stream()
                .flatMap(List::stream)
                .filter(f -> f.getStatus() == FunctionStatus.IMPLEMENTED)
                .count();
    }

    /**
     * Gets the total number of functions with errors.
     */
    public int getErrorCount() {
        return (int) functionsBySchema.values().stream()
                .flatMap(List::stream)
                .filter(f -> f.getStatus() == FunctionStatus.ERROR)
                .count();
    }

    /**
     * Gets the execution timestamp.
     */
    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    /**
     * Information about a single function.
     */
    public static class FunctionInfo {
        private final String functionName;
        private final String functionType; // FUNCTION or PROCEDURE
        private final boolean isPackageMember; // true if name contains __
        private FunctionStatus status;
        private String functionDdl;
        private String errorMessage;

        public FunctionInfo(String functionName, String functionType, boolean isPackageMember) {
            this.functionName = functionName;
            this.functionType = functionType;
            this.isPackageMember = isPackageMember;
        }

        public String getFunctionName() {
            return functionName;
        }

        public String getFunctionType() {
            return functionType;
        }

        public boolean isPackageMember() {
            return isPackageMember;
        }

        public FunctionStatus getStatus() {
            return status;
        }

        public void setStatus(FunctionStatus status) {
            this.status = status;
        }

        public String getFunctionDdl() {
            return functionDdl;
        }

        public void setFunctionDdl(String functionDdl) {
            this.functionDdl = functionDdl;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString() {
            return String.format("FunctionInfo{functionName='%s', type=%s, packageMember=%s, status=%s}",
                    functionName, functionType, isPackageMember, status);
        }
    }

    /**
     * Status of a function.
     */
    public enum FunctionStatus {
        STUB,         // Contains stub pattern (RETURN NULL, empty body) - placeholder not yet implemented
        IMPLEMENTED,  // Full function implementation with actual PL/pgSQL logic
        ERROR         // Could not retrieve DDL or function has errors
    }

    @Override
    public String toString() {
        return String.format("FunctionVerificationResult{total=%d, implemented=%d, stubs=%d, errors=%d, schemas=%d}",
                getTotalFunctions(), getImplementedCount(), getStubCount(), getErrorCount(), functionsBySchema.size());
    }
}
