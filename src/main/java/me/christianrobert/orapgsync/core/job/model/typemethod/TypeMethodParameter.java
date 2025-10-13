package me.christianrobert.orapgsync.core.job.model.typemethod;

/**
 * Represents a parameter for an Oracle object type method.
 * This is a pure data model without dependencies on other services.
 */
public class TypeMethodParameter {
    private String parameterName;
    private int position;
    private String dataType;
    private String dataTypeOwner; // Owner of custom data type (for user-defined types)
    private String dataTypeName; // Name of custom data type
    private String inOut; // IN, OUT, IN/OUT
    private boolean isCustomDataType; // true if this is a user-defined type

    public TypeMethodParameter(String parameterName, int position, String dataType) {
        this.parameterName = parameterName;
        this.position = position;
        this.dataType = dataType;
        this.isCustomDataType = false;
    }

    // Getters and setters
    public String getParameterName() {
        return parameterName;
    }

    public void setParameterName(String parameterName) {
        this.parameterName = parameterName;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDataTypeOwner() {
        return dataTypeOwner;
    }

    public void setDataTypeOwner(String dataTypeOwner) {
        this.dataTypeOwner = dataTypeOwner;
    }

    public String getDataTypeName() {
        return dataTypeName;
    }

    public void setDataTypeName(String dataTypeName) {
        this.dataTypeName = dataTypeName;
    }

    public String getInOut() {
        return inOut;
    }

    public void setInOut(String inOut) {
        this.inOut = inOut;
    }

    public boolean isCustomDataType() {
        return isCustomDataType;
    }

    public void setCustomDataType(boolean customDataType) {
        isCustomDataType = customDataType;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TypeMethodParameter{");
        sb.append("parameterName='").append(parameterName).append('\'');
        sb.append(", position=").append(position);
        sb.append(", dataType='").append(dataType).append('\'');
        if (isCustomDataType && dataTypeOwner != null) {
            sb.append(", dataTypeOwner='").append(dataTypeOwner).append('\'');
            sb.append(", dataTypeName='").append(dataTypeName).append('\'');
        }
        if (inOut != null) {
            sb.append(", inOut='").append(inOut).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
