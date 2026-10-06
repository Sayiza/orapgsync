package me.christianrobert.orapgsync.core.job.model.objectdatatype;

public class ObjectDataTypeVariable {
  private String name;
  private String dataType;
  private String dataTypeOwner; // Schema/owner of the data type (for custom types)

  public ObjectDataTypeVariable(String name, String dataType) {
    this.name = name;
    this.dataType = dataType;
    this.dataTypeOwner = null;
  }

  public ObjectDataTypeVariable(String name, String dataType, String dataTypeOwner) {
    this.name = name;
    this.dataType = dataType;
    this.dataTypeOwner = dataTypeOwner;
  }

  public String getName() {
    return name;
  }

  public String getDataType() {
    return dataType;
  }

  public String getDataTypeOwner() {
    return dataTypeOwner;
  }

  /**
   * Checks if this is a custom (user-defined) data type.
   * Custom types have an owner/schema specified.
   */
  public boolean isCustomDataType() {
    return dataTypeOwner != null && !dataTypeOwner.trim().isEmpty();
  }

  /**
   * Gets the fully qualified type name (schema.typename) for custom types,
   * or just the type name for built-in types.
   */
  public String getQualifiedTypeName() {
    if (isCustomDataType()) {
      return dataTypeOwner.toLowerCase() + "." + dataType.toLowerCase();
    }
    return dataType;
  }
}