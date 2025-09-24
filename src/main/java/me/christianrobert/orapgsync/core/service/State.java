package me.christianrobert.orapgsync.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.table.model.TableMetadata;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class State {
  // raw data from the database
  private List<String> userNames = new ArrayList<>();
  private List<ObjectDataTypeMetaData> objectDataTypeMetaData = new ArrayList<>();
  private List<TableMetadata> tableMetadata = new ArrayList<>();
  
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