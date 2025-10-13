package me.christianrobert.orapgsync.core.job.model.function;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents metadata for a database function or procedure.
 * This includes standalone functions/procedures and package subprograms.
 * This is a pure data model without dependencies on other services.
 */
public class FunctionMetadata {
    private String schema;
    private String objectName; // Function name for standalone, or function name for package subprograms
    private String packageName; // null for standalone functions/procedures
    private String objectType; // FUNCTION or PROCEDURE
    private String returnDataType; // null for procedures
    private boolean isCustomReturnType; // true if return type is a user-defined type
    private String returnTypeOwner; // Owner of custom return type
    private String returnTypeName; // Name of custom return type
    private int overloadNumber; // Oracle overload number (0 if not overloaded)
    private List<FunctionParameter> parameters;

    public FunctionMetadata(String schema, String objectName, String objectType) {
        this.schema = schema;
        this.objectName = objectName;
        this.objectType = objectType;
        this.parameters = new ArrayList<>();
        this.overloadNumber = 0;
    }

    // Getters and setters
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getReturnDataType() {
        return returnDataType;
    }

    public void setReturnDataType(String returnDataType) {
        this.returnDataType = returnDataType;
    }

    public boolean isCustomReturnType() {
        return isCustomReturnType;
    }

    public void setCustomReturnType(boolean customReturnType) {
        isCustomReturnType = customReturnType;
    }

    public String getReturnTypeOwner() {
        return returnTypeOwner;
    }

    public void setReturnTypeOwner(String returnTypeOwner) {
        this.returnTypeOwner = returnTypeOwner;
    }

    public String getReturnTypeName() {
        return returnTypeName;
    }

    public void setReturnTypeName(String returnTypeName) {
        this.returnTypeName = returnTypeName;
    }

    public int getOverloadNumber() {
        return overloadNumber;
    }

    public void setOverloadNumber(int overloadNumber) {
        this.overloadNumber = overloadNumber;
    }

    public List<FunctionParameter> getParameters() {
        return parameters;
    }

    public void addParameter(FunctionParameter parameter) {
        parameters.add(parameter);
    }

    /**
     * Checks if this is a standalone function or procedure.
     */
    public boolean isStandalone() {
        return packageName == null;
    }

    /**
     * Checks if this is a package subprogram.
     */
    public boolean isPackageMember() {
        return packageName != null;
    }

    /**
     * Checks if this is a function (has return type).
     */
    public boolean isFunction() {
        return "FUNCTION".equalsIgnoreCase(objectType);
    }

    /**
     * Checks if this is a procedure (no return type).
     */
    public boolean isProcedure() {
        return "PROCEDURE".equalsIgnoreCase(objectType);
    }

    /**
     * Gets the qualified name for PostgreSQL.
     * For standalone: schema.function_name
     * For package members: schema.packagename__functionname
     */
    public String getQualifiedName() {
        if (isPackageMember()) {
            return schema + "." + packageName.toLowerCase() + "__" + objectName.toLowerCase();
        } else {
            return schema + "." + objectName.toLowerCase();
        }
    }

    /**
     * Gets the PostgreSQL function name (without schema).
     * For standalone: function_name
     * For package members: packagename__functionname
     */
    public String getPostgresName() {
        if (isPackageMember()) {
            return packageName.toLowerCase() + "__" + objectName.toLowerCase();
        } else {
            return objectName.toLowerCase();
        }
    }

    /**
     * Gets a display name for logging and UI.
     * For standalone: schema.function_name
     * For package members: schema.package.function_name
     */
    public String getDisplayName() {
        if (isPackageMember()) {
            return schema + "." + packageName + "." + objectName;
        } else {
            return schema + "." + objectName;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("FunctionMetadata{");
        sb.append("schema='").append(schema).append('\'');
        sb.append(", objectName='").append(objectName).append('\'');
        if (packageName != null) {
            sb.append(", packageName='").append(packageName).append('\'');
        }
        sb.append(", objectType='").append(objectType).append('\'');
        if (returnDataType != null) {
            sb.append(", returnDataType='").append(returnDataType).append('\'');
        }
        sb.append(", parameters=").append(parameters.size());
        if (overloadNumber > 0) {
            sb.append(", overloadNumber=").append(overloadNumber);
        }
        sb.append('}');
        return sb.toString();
    }
}
