package me.christianrobert.orapgsync.objectdatatype.model;

public class ObjectDataTypeVariable {
  private String name;
  private String dataType;

  public ObjectDataTypeVariable(String name, String dataType) {
    this.name = name;
    this.dataType = dataType;
  }

  public String getName() {
    return name;
  }

  public String getDataType() {
    return dataType;
  }
}