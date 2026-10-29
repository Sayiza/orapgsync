package me.christianrobert.orapgsync.core.job.model.function;

import java.util.HashMap;
import java.util.Map;

/**
 * Result object for standalone function/procedure implementation operations.
 * Tracks which standalone functions were successfully implemented, skipped, or failed.
 * This is used for Phase 2 implementation (replacing stubs with actual PL/pgSQL logic).
 */
public class FunctionImplementationResult {
    private Map<String, FunctionMetadata> implementedFunctions;
    private Map<String, FunctionMetadata> skippedFunctions;
    private Map<String, ErrorInfo> errors;
    private Map<String, PackageErrorInfo> packageErrors;
    private Map<String, BlockedFunctionInfo> blockedFunctions;
    private int implementedCount;
    private int skippedCount;
    private int errorCount;
    private int packageErrorCount;
    private int blockedCount;

    public FunctionImplementationResult() {
        this.implementedFunctions = new HashMap<>();
        this.skippedFunctions = new HashMap<>();
        this.errors = new HashMap<>();
        this.packageErrors = new HashMap<>();
        this.blockedFunctions = new HashMap<>();
        this.implementedCount = 0;
        this.skippedCount = 0;
        this.errorCount = 0;
        this.packageErrorCount = 0;
        this.blockedCount = 0;
    }

    /**
     * Result-map key for a function, unique across Oracle overloads.
     *
     * <p>Neither {@code getQualifiedName()} nor {@code getDisplayName()} carries the overload
     * number, so two overloads of one package function produce the same key and the second
     * {@code put} silently replaces the first — while the counter still incremented. The
     * 2026-08-28 run reported {@code errorCount} 58,062 against an {@code errors} map holding
     * 56,338 entries, and 16,366 implemented against 16,046: <b>2,044 records overwritten, and the
     * counts advertised as if they were there</b>. Every {@code add*} method below therefore keys
     * through this method and increments only when the key was genuinely new, so a count can never
     * again exceed the evidence behind it.
     */
    private static String keyFor(FunctionMetadata function, String base) {
        return function.getOverloadNumber() > 0
                ? base + "#" + function.getOverloadNumber()
                : base;
    }

    public void addImplementedFunction(FunctionMetadata function) {
        String key = keyFor(function, function.getQualifiedName());
        if (implementedFunctions.put(key, function) == null) {
            implementedCount++;
        }
    }

    /**
     * <b>Currently has no caller, deliberately.</b> Skip must mean "there was nothing to do", and
     * function implementation has no such case — every function has a body to transform. Until
     * 2026-08-28 this held failed transformations, which reported 3,965 silent failures as a
     * neutral count; see {@code PostgresFunctionImplementationJob}. If a genuine
     * nothing-to-do case appears, give it a distinct exception type first, the way
     * {@code AbstractTypeMethodException} separates the two for type methods.
     */
    public void addSkippedFunction(FunctionMetadata function) {
        String key = function.getQualifiedName();
        skippedFunctions.put(key, function);
        skippedCount++;
    }

    public void addError(String functionName, String errorMessage, String sql) {
        if (errors.put(functionName, new ErrorInfo(functionName, errorMessage, sql)) == null) {
            errorCount++;
        }
    }

    /**
     * Records a failure of this function itself — its PL/SQL could not be transformed, or the
     * generated SQL was rejected by PostgreSQL. Overload-safe; see {@link #keyFor}.
     */
    public void addError(FunctionMetadata function, String errorMessage, String sql) {
        String key = keyFor(function, function.getDisplayName());
        ErrorInfo error = new ErrorInfo(function.getDisplayName(), errorMessage, sql);
        if (errors.put(key, error) == null) {
            errorCount++;
        }
    }

    /**
     * Records that a package's context could not be built, <b>once per package</b>.
     *
     * <p>Package context (variable getters/setters) is created lazily by the implementation job
     * before the first member function of a package is processed. When that fails, every member
     * function is unimplementable — so without this bucket the same root cause is recorded once per
     * member, against the member's name, with the package never named. On the 2026-08-28 run that
     * turned a few hundred broken packages into <b>44,693 opaque function errors, 79% of the job's
     * total</b>: 5,913 identical {@code schema "kindlib" does not exist} entries came from 300
     * packages, and the taxonomy read as if 5,913 functions each had their own bug.
     *
     * <p>The failing SQL belongs here and nowhere else — it is a property of the package's helper
     * generation, not of any one member.
     */
    public void addPackageError(String schema, String packageName, String errorMessage, String sql) {
        String key = (schema + "." + packageName).toLowerCase();
        PackageErrorInfo error = new PackageErrorInfo(schema, packageName, errorMessage, sql);
        if (packageErrors.put(key, error) == null) {
            packageErrorCount++;
        }
    }

    /**
     * Records a function that was never attempted because its package's context failed.
     *
     * <p><b>This is a failure, not a skip.</b> Skip means "there was nothing to do"; these
     * functions have a body and would very likely have transformed. They are separated from
     * {@link #addError} only so that one broken package cannot bury the genuine per-function
     * failures in the taxonomy — not because they are any less broken. {@link #isSuccessful()}
     * counts them, the summary line names them as blocked, and each entry names the package to
     * look at, which is where the fix actually is.
     */
    public void addBlockedFunction(FunctionMetadata function, String packageName, String reason) {
        String key = keyFor(function, function.getDisplayName());
        BlockedFunctionInfo blocked =
                new BlockedFunctionInfo(function.getDisplayName(), function.getSchema(), packageName, reason);
        if (blockedFunctions.put(key, blocked) == null) {
            blockedCount++;
        }
    }

    // Getters and setters
    public Map<String, FunctionMetadata> getImplementedFunctions() {
        return implementedFunctions;
    }

    public void setImplementedFunctions(Map<String, FunctionMetadata> implementedFunctions) {
        this.implementedFunctions = implementedFunctions;
        this.implementedCount = implementedFunctions.size();
    }

    public Map<String, FunctionMetadata> getSkippedFunctions() {
        return skippedFunctions;
    }

    public void setSkippedFunctions(Map<String, FunctionMetadata> skippedFunctions) {
        this.skippedFunctions = skippedFunctions;
        this.skippedCount = skippedFunctions.size();
    }

    public Map<String, ErrorInfo> getErrors() {
        return errors;
    }

    public void setErrors(Map<String, ErrorInfo> errors) {
        this.errors = errors;
        this.errorCount = errors.size();
    }

    public int getImplementedCount() {
        return implementedCount;
    }

    public void setImplementedCount(int implementedCount) {
        this.implementedCount = implementedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public Map<String, PackageErrorInfo> getPackageErrors() {
        return packageErrors;
    }

    public void setPackageErrors(Map<String, PackageErrorInfo> packageErrors) {
        this.packageErrors = packageErrors;
        this.packageErrorCount = packageErrors.size();
    }

    public int getPackageErrorCount() {
        return packageErrorCount;
    }

    public void setPackageErrorCount(int packageErrorCount) {
        this.packageErrorCount = packageErrorCount;
    }

    public Map<String, BlockedFunctionInfo> getBlockedFunctions() {
        return blockedFunctions;
    }

    public void setBlockedFunctions(Map<String, BlockedFunctionInfo> blockedFunctions) {
        this.blockedFunctions = blockedFunctions;
        this.blockedCount = blockedFunctions.size();
    }

    public int getBlockedCount() {
        return blockedCount;
    }

    public void setBlockedCount(int blockedCount) {
        this.blockedCount = blockedCount;
    }

    /**
     * <b>Blocked functions and package errors count against success.</b> They are failures that
     * happen to share a root cause, and a run that implemented nothing because every package
     * context failed must not report success — that is the defect this job already had once, in
     * the shape of {@code addSkippedFunction}.
     */
    public boolean isSuccessful() {
        return errorCount == 0 && packageErrorCount == 0 && blockedCount == 0;
    }

    /**
     * Error information for a failed function implementation.
     */
    public static class ErrorInfo {
        private String functionName;
        private String error;
        private String sql;

        public ErrorInfo(String functionName, String error, String sql) {
            this.functionName = functionName;
            this.error = error;
            this.sql = sql;
        }

        public String getFunctionName() {
            return functionName;
        }

        public void setFunctionName(String functionName) {
            this.functionName = functionName;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }

    /**
     * A package whose context (variable helper functions) could not be created, with the SQL
     * PostgreSQL rejected. One instance stands for every member function of that package.
     */
    public static class PackageErrorInfo {
        private String schema;
        private String packageName;
        private String error;
        private String sql;

        public PackageErrorInfo() {
        }

        public PackageErrorInfo(String schema, String packageName, String error, String sql) {
            this.schema = schema;
            this.packageName = packageName;
            this.error = error;
            this.sql = sql;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }

    /**
     * A function that was never attempted because its package's context failed. Names the package,
     * because that is where the fix is.
     */
    public static class BlockedFunctionInfo {
        private String functionName;
        private String schema;
        private String packageName;
        private String reason;

        public BlockedFunctionInfo() {
        }

        public BlockedFunctionInfo(String functionName, String schema, String packageName, String reason) {
            this.functionName = functionName;
            this.schema = schema;
            this.packageName = packageName;
            this.reason = reason;
        }

        public String getFunctionName() {
            return functionName;
        }

        public void setFunctionName(String functionName) {
            this.functionName = functionName;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    @Override
    public String toString() {
        return String.format(
                "FunctionImplementationResult{implemented=%d, errors=%d, blocked=%d, packageErrors=%d, skipped=%d}",
                implementedCount, errorCount, blockedCount, packageErrorCount, skippedCount);
    }
}
