package me.christianrobert.orapgsync.objectdatatype.model;

import java.util.List;

public class ObjectDataTypeMetaData {
  private String name;
  private String schema;

  // Object
  private List<ObjectDataTypeVariable> variables;

  public String getName() {
    return name;
  }

  public String getSchema() {
    return schema;
  }

  public List<ObjectDataTypeVariable> getVariables() {
    return variables;
  }
}