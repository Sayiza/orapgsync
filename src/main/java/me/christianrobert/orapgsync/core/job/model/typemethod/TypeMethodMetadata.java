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
    /** Raw ALL_TYPE_METHODS.METHOD_TYPE: PUBLIC, MAP or ORDER. Carries no function/procedure or
     *  member/static information -- see {@link TypeMethodKind} and the classifier. */
    private String methodType;
    private String methodNo; // Oracle method number for overloaded methods
    /** Raw ALL_TYPE_METHODS.INSTANTIABLE: concrete vs abstract, NOT member vs static. */
    private String instantiable;
    /** What this method actually is, derived by TypeMethodClassifier. */
    private TypeMethodKind kind;
    /** PARAM_MODE of the SELF row, or null when the method is static. IN or IN OUT. */
    private String selfParamMode;
    /** Zero-based rank among the overloads sharing this method name; -1 until assigned. */
    private int overloadOrdinal = -1;
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

    public TypeMethodKind getKind() {
        return kind;
    }

    public void setKind(TypeMethodKind kind) {
        this.kind = kind;
    }

    public String getSelfParamMode() {
        return selfParamMode;
    }

    public void setSelfParamMode(String selfParamMode) {
        this.selfParamMode = selfParamMode;
    }

    /**
     * Checks if this is a member method, i.e. one that takes SELF as a PostgreSQL parameter.
     *
     * <p>Constructors are excluded on purpose: they carry SELF in Oracle, but map to
     * {@code t(...) RETURNS t} with SELF as an injected local, not as a parameter.
     */
    public boolean isMemberMethod() {
        return kind != null && kind.hasSelf() && !kind.isConstructor();
    }

    /**
     * Checks if this is a static method (no SELF at all).
     */
    public boolean isStaticMethod() {
        return kind != null && kind.isStatic();
    }

    public boolean isConstructor() {
        return kind != null && kind.isConstructor();
    }

    /**
     * Checks if this is a function (has an ALL_METHOD_RESULTS row).
     */
    public boolean isFunction() {
        return kind != null && kind.returnsValue();
    }

    /**
     * Checks if this is a procedure (no ALL_METHOD_RESULTS row).
     */
    public boolean isProcedure() {
        return kind != null && kind.isProcedure();
    }

    /**
     * True when the method writes to SELF, signalled by {@code SELF IN OUT}. Call sites need the
     * statement-level rewrite {@code obj := t__m(obj, ...)}.
     */
    public boolean mutatesSelf() {
        return "IN OUT".equalsIgnoreCase(selfParamMode == null
                ? null
                : selfParamMode.trim().replaceAll("\\s+", " "));
    }

    /**
     * A member function that also writes to SELF. PostgreSQL cannot express INOUT parameters plus
     * an independent RETURNS, so this must be rejected rather than approximated -- see section 3.2
     * of the type method plan.
     */
    public boolean isMutatingMemberFunction() {
        return kind == TypeMethodKind.MEMBER_FUNCTION && mutatesSelf();
    }

    /**
     * Identity of the method within its schema, including {@code method_no} so that overloads stay
     * distinct. Oracle allows many methods to share a name on one type.
     */
    public String getIdentityKey() {
        return (schema + "." + typeName + "." + methodName + "#" + methodNo).toLowerCase();
    }

    public int getOverloadOrdinal() {
        return overloadOrdinal;
    }

    public void setOverloadOrdinal(int overloadOrdinal) {
        this.overloadOrdinal = overloadOrdinal;
    }

    /**
     * Key under which this method's Oracle source is stored. See {@link TypeMethodSourceKey}.
     */
    public String getSourceKey() {
        return TypeMethodSourceKey.of(methodName, overloadOrdinal);
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
     * Gets the method form for logging and UI, e.g. "MEMBER PROCEDURE" or "CONSTRUCTOR FUNCTION".
     */
    public String getMemberTypeDescription() {
        return kind == null ? "UNCLASSIFIED" : kind.display();
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
