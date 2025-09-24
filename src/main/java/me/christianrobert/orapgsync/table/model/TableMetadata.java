package me.christianrobert.orapgsync.table.model;

import me.christianrobert.orapgsync.core.service.State;

import java.util.ArrayList;
import java.util.List;

public class TableMetadata {
  private String schema; // Oracle schema (user)
  private String tableName;
  private List<ColumnMetadata> columns;
  private List<ConstraintMetadata> constraints;

  public TableMetadata(String schema, String tableName) {
    this.schema = schema;
    this.tableName = tableName;
    this.columns = new ArrayList<>();
    this.constraints = new ArrayList<>();
  }

  // Getters and setters
  public String getSchema() { return schema; }
  public String getTableName() { return tableName; }
  public List<ColumnMetadata> getColumns() { return columns; }
  public List<ConstraintMetadata> getConstraints() { return constraints; }

  public void addColumn(ColumnMetadata column) { columns.add(column); }
  public void addConstraint(ConstraintMetadata constraint) { constraints.add(constraint); }

  @Override
  public String toString() {
    return "TableMetadata{schema='" + schema + "', tableName='" + tableName + "', columns=" + columns.size() + ", constraints=" + constraints.size() + "}";
  }

  public String toJava(String javaPackageName) {
    return "TODO entity";
  }

  public List<String> toPostgre(State data) {
    return null;
  }
}