package me.christianrobert.orapgsync.transformer.packagevariable;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Ephemeral package context holding variable declarations from package spec
 * and parsed package body AST for function source extraction.
 *
 * <p>This context exists only during transformation job execution and is cached in-memory
 * for the duration of the job.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Created when first function from a package is encountered during transformation</li>
 *   <li>Package spec parsed → variables extracted</li>
 *   <li>Package body parsed → AST cached (for multi-function extraction efficiency)</li>
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

    // Package body cache (for efficient multi-function extraction)
    // Avoids re-querying and re-parsing the same package body for each function
    private String packageBodySource;  // Full source text (for character-index extraction)
    private PlSqlParser.Create_package_bodyContext packageBodyAst;  // Parsed AST

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

    /**
     * Caches the parsed package body for efficient function extraction.
     * Called once per package during ensurePackageContext().
     *
     * @param bodySource Full package body source text
     * @param bodyAst Parsed package body AST
     */
    public void setPackageBody(String bodySource, PlSqlParser.Create_package_bodyContext bodyAst) {
        this.packageBodySource = bodySource;
        this.packageBodyAst = bodyAst;
    }

    /**
     * Checks if package body is cached.
     *
     * @return true if package body AST is available
     */
    public boolean hasPackageBody() {
        return packageBodyAst != null;
    }

    /**
     * Extracts source code for a specific function/procedure from the cached package body.
     * Uses character index slicing to preserve original Oracle formatting.
     *
     * <p>This method enables efficient extraction when a package has multiple functions:
     * <ul>
     *   <li>Package body parsed once → AST cached</li>
     *   <li>Each function extracted from cached AST (no re-parsing)</li>
     * </ul>
     *
     * @param functionName Function/procedure name (without package prefix)
     * @param isFunction true for function, false for procedure
     * @return Oracle PL/SQL source code for the function, or null if not found
     */
    public String extractFunctionSource(String functionName, boolean isFunction) {
        if (packageBodyAst == null || packageBodySource == null) {
            return null;
        }

        String normalizedName = functionName.toLowerCase();

        // Search through package body objects for matching function/procedure
        for (PlSqlParser.Package_obj_bodyContext objCtx : packageBodyAst.package_obj_body()) {

            // Try function_body
            if (isFunction && objCtx.function_body() != null) {
                PlSqlParser.Function_bodyContext funcCtx = objCtx.function_body();
                if (funcCtx.identifier().getText().equalsIgnoreCase(normalizedName)) {
                    return extractSourceFromContext(funcCtx);
                }
            }

            // Try procedure_body
            if (!isFunction && objCtx.procedure_body() != null) {
                PlSqlParser.Procedure_bodyContext procCtx = objCtx.procedure_body();
                if (procCtx.identifier().getText().equalsIgnoreCase(normalizedName)) {
                    return extractSourceFromContext(procCtx);
                }
            }
        }

        return null;  // Function/procedure not found in package body
    }

    /**
     * Extracts source text from a parse tree context using character indices.
     * Preserves exact Oracle formatting, whitespace, and comments.
     */
    private String extractSourceFromContext(org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null || packageBodySource == null) {
            return null;
        }

        int start = ctx.getStart().getStartIndex();
        int stop = ctx.getStop().getStopIndex();

        // Bounds check
        if (start < 0 || stop >= packageBodySource.length() || start > stop) {
            return null;
        }

        return packageBodySource.substring(start, stop + 1);
    }

    @Override
    public String toString() {
        return "PackageContext{" +
                "schema='" + schema + '\'' +
                ", packageName='" + packageName + '\'' +
                ", variables=" + variables.size() +
                ", helpersCreated=" + helpersCreated +
                ", hasPackageBody=" + (packageBodyAst != null) +
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
