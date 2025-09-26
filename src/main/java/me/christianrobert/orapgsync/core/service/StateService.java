package me.christianrobert.orapgsync.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.table.model.TableMetadata;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class StateService {
  // raw data from the source database
  private List<String> oracleSchemaNames = new ArrayList<>();
  private List<ObjectDataTypeMetaData> oracleObjectDataTypeMetaData = new ArrayList<>();
  private List<TableMetadata> oracleTableMetadata = new ArrayList<>();

  // raw data from the target database
  private List<String> postgresSchemaNames = new ArrayList<>();
  private List<TableMetadata> postgresTableMetadata = new ArrayList<>();

  // Getter methods
  public List<String> getOracleSchemaNames() {
    return new ArrayList<>(oracleSchemaNames);
  }

  public List<String> getPostgresSchemaNames() {
    return new ArrayList<>(postgresSchemaNames);
  }

  public List<ObjectDataTypeMetaData> getOracleObjectDataTypeMetaData() {
    return new ArrayList<>(oracleObjectDataTypeMetaData);
  }

  public List<TableMetadata> getOracleTableMetadata() {
    return new ArrayList<>(oracleTableMetadata);
  }

  public List<TableMetadata> getPostgresTableMetadata() {
    return new ArrayList<>(postgresTableMetadata);
  }

  // Management methods for Oracle schemas
  public void updateOracleSchemaNames(List<String> schemas) {
    this.oracleSchemaNames.clear();
    this.oracleSchemaNames.addAll(schemas);
  }

  // Management methods for PostgreSQL schemas
  public void updatePostgresSchemaNames(List<String> schemas) {
    this.postgresSchemaNames.clear();
    this.postgresSchemaNames.addAll(schemas);
  }

  // Management methods for Oracle object data types
  public void updateOracleObjectDataTypeMetaData(List<ObjectDataTypeMetaData> objectDataTypes) {
    this.oracleObjectDataTypeMetaData.clear();
    this.oracleObjectDataTypeMetaData.addAll(objectDataTypes);
  }

  // Management methods for Oracle table metadata
  public void updateOracleTableMetadata(List<TableMetadata> tableMetadata) {
    this.oracleTableMetadata.clear();
    this.oracleTableMetadata.addAll(tableMetadata);
  }

  // Management methods for PostgreSQL table metadata
  public void updatePostgresTableMetadata(List<TableMetadata> tableMetadata) {
    this.postgresTableMetadata.clear();
    this.postgresTableMetadata.addAll(tableMetadata);
  }

  /*
  private List<ViewMetadata> viewDefinition = new ArrayList<>();
  private List<SynonymMetadata> synonyms = new ArrayList<>();
  private List<IndexMetadata> indexes = new ArrayList<>();
  private List<PlsqlCode> objectTypeSpecPlsql = new ArrayList<>();
  private List<PlsqlCode> objectTypeBodyPlsql = new ArrayList<>();
  private List<PlsqlCode> packageSpecPlsql = new ArrayList<>();
  private List<PlsqlCode> packageBodyPlsql = new ArrayList<>();
  private List<PlsqlCode> standaloneFunctionPlsql = new ArrayList<>();
  private List<PlsqlCode> standaloneProcedurePlsql = new ArrayList<>();
  private List<PlsqlCode> triggerPlsql = new ArrayList<>();

  // parsed data
  private List<ViewSpecAndQuery> viewSpecAndQueries = new ArrayList<>();
  private List<ObjectType> objectTypeSpecAst = new ArrayList<>();
  private List<ObjectType> objectTypeBodyAst = new ArrayList<>();
  private List<OraclePackage> packageSpecAst = new ArrayList<>();
  private List<OraclePackage> packageBodyAst = new ArrayList<>();
  private List<Function> standaloneFunctionAst = new ArrayList<>();
  private List<Procedure> standaloneProcedureAst = new ArrayList<>();
  private List<Trigger> triggerAst = new ArrayList<>();
  */
}