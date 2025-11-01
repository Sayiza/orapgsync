package me.christianrobert.orapgsync.core.job.model.function;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of PostgreSQL function implementation verification.
 * Contains lists of verified (implemented) and failed (stub/error) functions
 * with their DDL and metadata for frontend display.
 */
public class FunctionImplementationVerificationResult {
    private List<VerifiedFunction> verifiedFunctions = new ArrayList<>();
    private List<VerifiedFunction> failedFunctions = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private Map<String, String> failureReasons = new HashMap<>();

    /**
     * Represents a verified function with its DDL and metadata.
     */
    public static class VerifiedFunction {
        private final String schema;
        private final String functionName;
        private final String objectType; // FUNCTION or PROCEDURE
        private final String returnType; // null for procedures
        private final String ddl;
        private final int lineCount;
        private final boolean isStub;
        private final String signature; // Human-readable signature for display

        public VerifiedFunction(String schema, String functionName, String objectType,
                              String returnType, String ddl, boolean isStub, String signature) {
            this.schema = schema;
            this.functionName = functionName;
            this.objectType = objectType;
            this.returnType = returnType;
            this.ddl = ddl;
            this.isStub = isStub;
            this.signature = signature;
            this.lineCount = ddl != null ? ddl.split("\n").length : 0;
        }

        // Getters
        public String getSchema() { return schema; }
        public String getFunctionName() { return functionName; }
        public String getQualifiedName() { return schema + "." + functionName; }
        public String getObjectType() { return objectType; }
        public String getReturnType() { return returnType; }
        public String getDdl() { return ddl; }
        public int getLineCount() { return lineCount; }
        public boolean isStub() { return isStub; }
        public String getSignature() { return signature; }
    }

    // Add verified function
    public void addVerifiedFunction(String schema, String functionName, String objectType,
                                   String returnType, String ddl, boolean isStub, String signature) {
        verifiedFunctions.add(new VerifiedFunction(schema, functionName, objectType,
                                                   returnType, ddl, isStub, signature));
    }

    // Add failed function
    public void addFailedFunction(String schema, String functionName, String objectType,
                                 String returnType, String ddl, boolean isStub,
                                 String signature, String failureReason) {
        VerifiedFunction func = new VerifiedFunction(schema, functionName, objectType,
                                                     returnType, ddl, isStub, signature);
        failedFunctions.add(func);
        failureReasons.put(func.getQualifiedName(), failureReason);
    }

    // Add warning
    public void addWarning(String warning) {
        warnings.add(warning);
    }

    // Getters
    public List<VerifiedFunction> getVerifiedFunctions() {
        return verifiedFunctions;
    }

    public List<VerifiedFunction> getFailedFunctions() {
        return failedFunctions;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Map<String, String> getFailureReasons() {
        return failureReasons;
    }

    public int getVerifiedCount() {
        return verifiedFunctions.size();
    }

    public int getFailedCount() {
        return failedFunctions.size();
    }

    public int getWarningCount() {
        return warnings.size();
    }

    public boolean isSuccessful() {
        return failedFunctions.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("FunctionImplementationVerificationResult[verified=%d, failed=%d, warnings=%d]",
                getVerifiedCount(), getFailedCount(), getWarningCount());
    }
}
