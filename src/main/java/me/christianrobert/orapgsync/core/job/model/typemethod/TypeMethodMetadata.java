package me.christianrobert.orapgsync.core.job.model.typemethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents metadata for an Oracle object type method (member function/procedure).
 * This includes member methods, static methods, constructor methods, etc.
 * This is a pure data model without dependencies on other services.
 */
public class TypeMethodMetadata {
    private String schema;
    private String typeName;
    private String methodName;
    private String methodType; // FUNCTION or PROCEDURE
    private String methodNo; // Oracle method number for overloaded methods
    private String instantiable; // YES or NO - indicates if method is STATIC or MEMBER
    private String returnDataType; // null for procedures
    private boolean isCustomReturnType; // true if return type is a user-defined type
    private String returnTypeOwner; // Owner of custom return type
    private String returnTypeName; // Name of custom return type
    private List<TypeMethodParameter> parameters;

    public TypeMethodMetadata(String schema, String typeName, String methodName, String methodType) {
        this.schema = schema;
        this.typeName = typeName;
        this.methodName = methodName;
        this.methodType = methodType;
        this.parameters = new ArrayList<>();
    }

    // Getters and setters
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getMethodType() {
        return methodType;
    }

    public void setMethodType(String methodType) {
        this.methodType = methodType;
    }

    public String getMethodNo() {
        return methodNo;
    }

    public void setMethodNo(String methodNo) {
        this.methodNo = methodNo;
    }

    public String getInstantiable() {
        return instantiable;
    }

    public void setInstantiable(String instantiable) {
        this.instantiable = instantiable;
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

    public List<TypeMethodParameter> getParameters() {
        return parameters;
    }

    public void addParameter(TypeMethodParameter parameter) {
        parameters.add(parameter);
    }

    /**
     * Checks if this is a member method (requires SELF parameter).
     * Member methods have instantiable = 'YES'.
     */
    public boolean isMemberMethod() {
        return "YES".equalsIgnoreCase(instantiable);
    }

    /**
     * Checks if this is a static method (no SELF parameter).
     * Static methods have instantiable = 'NO'.
     */
    public boolean isStaticMethod() {
        return "NO".equalsIgnoreCase(instantiable);
    }

    /**
     * Checks if this is a function (has return type).
     */
    public boolean isFunction() {
        return "FUNCTION".equalsIgnoreCase(methodType);
    }

    /**
     * Checks if this is a procedure (no return type).
     */
    public boolean isProcedure() {
        return "PROCEDURE".equalsIgnoreCase(methodType);
    }

    /**
     * Gets the qualified name for PostgreSQL.
     * Pattern: schema.typename__methodname
     */
    public String getQualifiedName() {
        return schema + "." + typeName.toLowerCase() + "__" + methodName.toLowerCase();
    }

    /**
     * Gets the PostgreSQL function name (without schema).
     * Pattern: typename__methodname
     */
    public String getPostgresName() {
        return typeName.toLowerCase() + "__" + methodName.toLowerCase();
    }

    /**
     * Gets a display name for logging and UI.
     * Pattern: schema.typename.methodname
     */
    public String getDisplayName() {
        return schema + "." + typeName + "." + methodName;
    }

    /**
     * Gets the member type description (MEMBER or STATIC).
     */
    public String getMemberTypeDescription() {
        return isMemberMethod() ? "MEMBER" : "STATIC";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TypeMethodMetadata{");
        sb.append("schema='").append(schema).append('\'');
        sb.append(", typeName='").append(typeName).append('\'');
        sb.append(", methodName='").append(methodName).append('\'');
        sb.append(", methodType='").append(methodType).append('\'');
        sb.append(", instantiable='").append(instantiable).append('\'');
        if (returnDataType != null) {
            sb.append(", returnDataType='").append(returnDataType).append('\'');
        }
        sb.append(", parameters=").append(parameters.size());
        if (methodNo != null) {
            sb.append(", methodNo=").append(methodNo);
        }
        sb.append('}');
        return sb.toString();
    }
}
