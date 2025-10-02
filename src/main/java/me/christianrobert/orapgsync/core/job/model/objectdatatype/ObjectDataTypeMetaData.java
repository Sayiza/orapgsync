package me.christianrobert.orapgsync.core.job.model.objectdatatype;

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