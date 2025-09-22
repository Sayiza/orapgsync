package me.christianrobert.orapgsync.objectmeta.model;

import java.util.List;

public class ObjectTypeMetaData {
  private String name;
  private String schema;

  // Object
  private List<ObjectTypeVariable> variables;

  public String getName() {
    return name;
  }

  public String getSchema() {
    return schema;
  }

  public List<ObjectTypeVariable> getVariables() {
    return variables;
  }
}
