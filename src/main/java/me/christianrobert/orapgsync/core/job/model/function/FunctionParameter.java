package me.christianrobert.orapgsync.core.job.model.function;

/**
 * Represents a parameter of a function or procedure.
 * This is a pure data model without dependencies on other services.
 */
public class FunctionParameter {
    private String parameterName;
    private int position;
    private String dataType;
    private String inOut; // IN, OUT, IN OUT
    private boolean isCustomDataType;
    private String dataTypeOwner;
    private String dataTypeName;

    public FunctionParameter(String parameterName, int position, String dataType, String inOut) {
        this.parameterName = parameterName;
        this.position = position;
        this.dataType = dataType;
        this.inOut = inOut;
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

    @Override
    public String toString() {
        return "FunctionParameter{" +
                "parameterName='" + parameterName + '\'' +
                ", position=" + position +
                ", dataType='" + dataType + '\'' +
                ", inOut='" + inOut + '\'' +
                ", isCustomDataType=" + isCustomDataType +
                (isCustomDataType ? ", dataTypeOwner='" + dataTypeOwner + "', dataTypeName='" + dataTypeName + '\'' : "") +
                '}';
    }
}
