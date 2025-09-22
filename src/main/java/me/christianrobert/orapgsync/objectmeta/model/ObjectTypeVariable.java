package me.christianrobert.orapgsync.objectmeta.model;

public class ObjectTypeVariable {
  private String name;
  private String dataType;

  public ObjectTypeVariable(String name, String dataType) {
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
