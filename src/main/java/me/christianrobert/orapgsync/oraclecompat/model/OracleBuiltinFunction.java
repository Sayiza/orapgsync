package me.christianrobert.orapgsync.oraclecompat.model;

/**
 * Represents a single Oracle built-in function and its PostgreSQL equivalent.
 * <p>
 * This model encapsulates the metadata for an Oracle built-in package function
 * along with its PostgreSQL replacement implementation, support level, and documentation.
 */
public class OracleBuiltinFunction {
    private final String packageName;        // e.g., "DBMS_OUTPUT"
    private final String functionName;       // e.g., "PUT_LINE"
    private final String signature;          // e.g., "PUT_LINE(text)"
    private final SupportLevel supportLevel;
    private final String postgresFunction;   // e.g., "oracle_compat.dbms_output__put_line"
    private final String notes;              // Limitations, differences from Oracle
    private final String sqlDefinition;      // PostgreSQL function definition

    private OracleBuiltinFunction(Builder builder) {
        this.packageName = builder.packageName;
        this.functionName = builder.functionName;
        this.signature = builder.signature;
        this.supportLevel = builder.supportLevel;
        this.postgresFunction = builder.postgresFunction;
        this.notes = builder.notes;
        this.sqlDefinition = builder.sqlDefinition;
    }

    /**
     * Returns the full Oracle function name (e.g., "DBMS_OUTPUT.PUT_LINE").
     */
    public String getFullOracleName() {
        return packageName + "." + functionName;
    }

    /**
     * Returns the PostgreSQL function name using double-underscore flattening
     * (e.g., "dbms_output__put_line").
     */
    public String getPostgresFlatName() {
        return packageName.toLowerCase() + "__" + functionName.toLowerCase();
    }

    // Getters

    public String getPackageName() {
        return packageName;
    }

    public String getFunctionName() {
        return functionName;
    }

    public String getSignature() {
        return signature;
    }

    public SupportLevel getSupportLevel() {
        return supportLevel;
    }

    public String getPostgresFunction() {
        return postgresFunction;
    }

    public String getNotes() {
        return notes;
    }

    public String getSqlDefinition() {
        return sqlDefinition;
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String packageName;
        private String functionName;
        private String signature;
        private SupportLevel supportLevel;
        private String postgresFunction;
        private String notes;
        private String sqlDefinition;

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder functionName(String functionName) {
            this.functionName = functionName;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder supportLevel(SupportLevel supportLevel) {
            this.supportLevel = supportLevel;
            return this;
        }

        public Builder postgresFunction(String postgresFunction) {
            this.postgresFunction = postgresFunction;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Builder sqlDefinition(String sqlDefinition) {
            this.sqlDefinition = sqlDefinition;
            return this;
        }

        public OracleBuiltinFunction build() {
            return new OracleBuiltinFunction(this);
        }
    }

    @Override
    public String toString() {
        return "OracleBuiltinFunction{" +
                "packageName='" + packageName + '\'' +
                ", functionName='" + functionName + '\'' +
                ", supportLevel=" + supportLevel +
                '}';
    }
}
