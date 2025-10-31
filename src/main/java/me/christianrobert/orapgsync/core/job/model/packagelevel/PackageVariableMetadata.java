package me.christianrobert.orapgsync.core.job.model.packagelevel;

/**
 * Represents metadata for a package-level variable declaration.
 * This is a pure data model without dependencies on other services.
 *
 * <p>Package variables are declared in Oracle package specifications and maintain
 * session-level state. This metadata is used to generate PostgreSQL helper functions
 * that emulate Oracle package variable semantics using set_config/current_setting.
 */
public class PackageVariableMetadata {
    private String schema;
    private String packageName;
    private String variableName;
    private String dataType;        // e.g., "INTEGER", "VARCHAR2(100)", "DATE"
    private String defaultValue;    // e.g., "0", "'ACTIVE'", "SYSDATE" (may be null)
    private boolean isConstant;     // true if declared with CONSTANT keyword

    /**
     * Constructs package variable metadata with required fields.
     *
     * @param schema Schema name
     * @param packageName Package name
     * @param variableName Variable name
     * @param dataType Oracle data type
     */
    public PackageVariableMetadata(String schema, String packageName, String variableName, String dataType) {
        this.schema = schema;
        this.packageName = packageName;
        this.variableName = variableName;
        this.dataType = dataType;
        this.isConstant = false; // default to non-constant
    }

    // Getters and setters

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

    public String getVariableName() {
        return variableName;
    }

    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isConstant() {
        return isConstant;
    }

    public void setConstant(boolean constant) {
        isConstant = constant;
    }

    /**
     * Returns a qualified key for this variable: schema.package.variable
     * Used for indexing and lookups.
     */
    public String getQualifiedKey() {
        return schema.toLowerCase() + "." + packageName.toLowerCase() + "." + variableName.toLowerCase();
    }

    @Override
    public String toString() {
        return "PackageVariableMetadata{" +
                "schema='" + schema + '\'' +
                ", packageName='" + packageName + '\'' +
                ", variableName='" + variableName + '\'' +
                ", dataType='" + dataType + '\'' +
                ", defaultValue='" + defaultValue + '\'' +
                ", isConstant=" + isConstant +
                '}';
    }
}
