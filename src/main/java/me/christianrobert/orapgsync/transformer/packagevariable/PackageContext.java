package me.christianrobert.orapgsync.transformer.packagevariable;

import java.util.HashMap;
import java.util.Map;

/**
 * Ephemeral package context holding variable declarations extracted from Oracle package spec.
 * This context exists only during transformation job execution and is cached in-memory
 * for the duration of the job.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Created when first function from a package is encountered during transformation</li>
 *   <li>Cached in-memory for job execution duration</li>
 *   <li>Garbage collected when job completes</li>
 * </ul>
 *
 * <p>NOT stored in StateService - this is transformation-time context, not metadata.
 */
public class PackageContext {

    private final String schema;
    private final String packageName;
    private final Map<String, PackageVariable> variables;
    private boolean helpersCreated;

    /**
     * Creates a new package context.
     *
     * @param schema Schema name (e.g., "HR")
     * @param packageName Package name (e.g., "EMP_PKG")
     */
    public PackageContext(String schema, String packageName) {
        this.schema = schema;
        this.packageName = packageName;
        this.variables = new HashMap<>();
        this.helpersCreated = false;
    }

    /**
     * Adds a variable to this package context.
     *
     * @param variable The package variable to add
     */
    public void addVariable(PackageVariable variable) {
        this.variables.put(variable.getVariableName().toLowerCase(), variable);
    }

    /**
     * Gets a variable by name (case-insensitive).
     *
     * @param variableName Variable name
     * @return PackageVariable or null if not found
     */
    public PackageVariable getVariable(String variableName) {
        return variables.get(variableName.toLowerCase());
    }

    /**
     * Checks if a variable exists in this package (case-insensitive).
     *
     * @param variableName Variable name
     * @return true if variable exists
     */
    public boolean hasVariable(String variableName) {
        return variables.containsKey(variableName.toLowerCase());
    }

    /**
     * Gets the cache key for this package context.
     * Format: "schema.packagename" (lowercase)
     *
     * @return Cache key
     */
    public String getCacheKey() {
        return (schema + "." + packageName).toLowerCase();
    }

    // Getters and setters

    public String getSchema() {
        return schema;
    }

    public String getPackageName() {
        return packageName;
    }

    public Map<String, PackageVariable> getVariables() {
        return variables;
    }

    public boolean isHelpersCreated() {
        return helpersCreated;
    }

    public void setHelpersCreated(boolean helpersCreated) {
        this.helpersCreated = helpersCreated;
    }

    @Override
    public String toString() {
        return "PackageContext{" +
                "schema='" + schema + '\'' +
                ", packageName='" + packageName + '\'' +
                ", variables=" + variables.size() +
                ", helpersCreated=" + helpersCreated +
                '}';
    }

    /**
     * Represents a single package-level variable declaration.
     */
    public static class PackageVariable {

        private final String variableName;
        private final String dataType;
        private final String defaultValue;
        private final boolean isConstant;

        /**
         * Creates a new package variable.
         *
         * @param variableName Variable name (e.g., "G_COUNTER")
         * @param dataType Oracle data type (e.g., "INTEGER", "VARCHAR2(100)", "DATE")
         * @param defaultValue Default value expression (e.g., "0", "'ACTIVE'", "SYSDATE")
         * @param isConstant true if declared with CONSTANT keyword
         */
        public PackageVariable(String variableName, String dataType, String defaultValue, boolean isConstant) {
            this.variableName = variableName;
            this.dataType = dataType;
            this.defaultValue = defaultValue;
            this.isConstant = isConstant;
        }

        // Getters

        public String getVariableName() {
            return variableName;
        }

        public String getDataType() {
            return dataType;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public boolean isConstant() {
            return isConstant;
        }

        @Override
        public String toString() {
            return "PackageVariable{" +
                    "variableName='" + variableName + '\'' +
                    ", dataType='" + dataType + '\'' +
                    ", defaultValue='" + defaultValue + '\'' +
                    ", isConstant=" + isConstant +
                    '}';
        }
    }
}
