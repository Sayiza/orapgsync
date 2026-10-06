package me.christianrobert.orapgsync.core.job.model.objectdatatype;

import java.util.List;

public class ObjectDataTypeMetaData {
  private String name;
  private String schema;

  // Object
  private List<ObjectDataTypeVariable> variables;

  /**
   * Default constructor for frameworks that require no-arg constructors.
   */
  public ObjectDataTypeMetaData() {
  }

  /**
   * Constructor for creating object type metadata.
   *
   * @param schema The schema/owner of the object type
   * @param name The name of the object type
   * @param variables The list of variables/attributes in the object type
   */
  public ObjectDataTypeMetaData(String schema, String name, List<ObjectDataTypeVariable> variables) {
    this.schema = schema;
    this.name = name;
    this.variables = variables;
  }

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