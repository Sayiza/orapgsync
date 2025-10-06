package me.christianrobert.orapgsync.table.service;

import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class OracleTableExtractor {

  private static final Logger log = LoggerFactory.getLogger(OracleTableExtractor.class);

  public static List<TableMetadata> extractAllTables(Connection oracleConn, List<String> users) throws SQLException {
    List<TableMetadata> tableMetadataList = new ArrayList<>();

    for (String user : users) {
      if (UserExcluder.is2BeExclueded(user)) {
        continue;
      }

      List<String> tables = fetchTableNames(oracleConn, user);

      for (String table : tables) {
        //if (table.matches("SYS_IOT_OVER_.*|BIN\\$.*|BW_STUDIUM_SEM_CFG_BAK\\$.*|DR\\$.*|MLOG\\$_.*|RUPD\\$_.*|AQ\\$.*|QUEUE_TABLE.*|ISEQ\\$\\$_.*|SYS_LOB.*|LOB\\$.*|WRI\\$_.*|SHSPACE.*|SQL\\$.*")) {
        //  continue; // Skip internal/system tables
        //}

        // Check if table is global temporary
        if (isGlobalTemporaryTable(oracleConn, user, table)) {
          continue;
        }

        TableMetadata tableMetadata =
                fetchTableMetadata(oracleConn, user, table);
        tableMetadataList.add(tableMetadata);
      }
      log.info("Extracted tables from schema {}", user);
    }
    return tableMetadataList;
  }

  private static List<String> fetchTableNames(Connection oracleConn, String owner) throws SQLException {
    List<String> result = new ArrayList<>();
    String sql = "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY table_name";

    try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
      ps.setString(1, owner.toUpperCase());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString("table_name"));
        }
      }
    }
    return result;
  }

  private static boolean isGlobalTemporaryTable(Connection oracleConn, String owner, String table) throws SQLException {
    String sql = "SELECT temporary FROM all_tables WHERE owner = ? AND table_name = ?";
    try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
      ps.setString(1, owner.toUpperCase());
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return "Y".equals(rs.getString("temporary"));
        }
      }
    }
    return false;
  }

  private static TableMetadata fetchTableMetadata(Connection oracleConn, String owner, String table) throws SQLException {
    TableMetadata tableMetadata = new TableMetadata(owner.toLowerCase(), table.toLowerCase());

    // Fetch column metadata (exclude hidden, virtual, and system-generated columns)
    // AND hidden_column = 'NO' AND virtual_column = 'NO'
    String columnSql = "SELECT column_name, data_type, data_type_owner, char_length, data_precision, data_scale, nullable, data_default " +
            "FROM all_tab_cols WHERE owner = ? AND table_name = ? " +
            "AND user_generated = 'YES' " +
            "ORDER BY column_id";
    try (PreparedStatement ps = oracleConn.prepareStatement(columnSql)) {
      ps.setString(1, owner.toUpperCase());
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String columnName = rs.getString("column_name").toLowerCase();
          String dataType = rs.getString("data_type");
          String dataTypeOwner = rs.getString("data_type_owner");
          // Keep all type owner information (including SYS) for proper type classification
          // PostgresTableCreationJob.isComplexOracleSystemType() will determine if SYS types
          // should be mapped to jsonb vs user-defined types mapped to composite types
          if (dataTypeOwner != null && !dataTypeOwner.isEmpty()) {
            dataTypeOwner = dataTypeOwner.toLowerCase();
          } else {
            dataTypeOwner = null;
          }
          Integer charLength = rs.getInt("char_length");
          if (rs.wasNull()) charLength = null;
          Integer precision = rs.getInt("data_precision");
          if (rs.wasNull()) precision = null;
          Integer scale = rs.getInt("data_scale");
          if (rs.wasNull()) scale = null;
          boolean nullable = "Y".equals(rs.getString("nullable"));
          String defaultValue = rs.getString("data_default");
          if (defaultValue != null) {
            defaultValue = defaultValue.trim();
          }

          ColumnMetadata column = new ColumnMetadata(columnName, dataType, dataTypeOwner, charLength, precision, scale, nullable, defaultValue);
          tableMetadata.addColumn(column);
        }
      }
    }

    // Fetch all constraints (PRIMARY KEY, FOREIGN KEY, UNIQUE, CHECK)
    fetchConstraints(oracleConn, owner, table, tableMetadata);

    return tableMetadata;
  }

  /**
   * Fetches all constraints for a table including PRIMARY KEY, FOREIGN KEY, UNIQUE, and CHECK constraints.
   * 
   * @param oracleConn Oracle database connection
   * @param owner Schema owner (Oracle user)
   * @param table Table name
   * @param tableMetadata TableMetadata object to add constraints to
   * @throws SQLException if database operations fail
   */
  private static void fetchConstraints(Connection oracleConn, String owner, String table, 
                                     TableMetadata tableMetadata) throws SQLException {
    // Enhanced SQL to fetch comprehensive constraint metadata
    String constraintSql = "SELECT ac.constraint_name, ac.constraint_type, ac.status, " +
            "ac.deferrable, ac.deferred, ac.validated, ac.index_name, " +
            "ac.search_condition, ac.r_owner, ac.r_constraint_name, ac.delete_rule " +
            "FROM all_constraints ac " +
            "WHERE ac.owner = ? AND ac.table_name = ? " +
            "AND ac.constraint_type IN ('P', 'R', 'U', 'C') " +
            "ORDER BY ac.constraint_type, ac.constraint_name";

    try (PreparedStatement ps = oracleConn.prepareStatement(constraintSql)) {
      ps.setString(1, owner.toUpperCase());
      ps.setString(2, table);
      
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String constraintName = rs.getString("constraint_name");
          String constraintType = rs.getString("constraint_type");
          String status = rs.getString("status");
          String deferrable = rs.getString("deferrable");
          String deferred = rs.getString("deferred");
          String validated = rs.getString("validated");
          String indexName = rs.getString("index_name");
          String searchCondition = rs.getString("search_condition");
          String referencedOwner = rs.getString("r_owner");
          String referencedConstraintName = rs.getString("r_constraint_name");
          String deleteRule = rs.getString("delete_rule");

          // Create constraint metadata based on type
          ConstraintMetadata constraint;
          if ("R".equals(constraintType) && referencedOwner != null && referencedConstraintName != null) {
            // Foreign key constraint - need to resolve referenced table
            String[] referencedTableInfo = resolveReferencedTable(oracleConn, referencedOwner, referencedConstraintName);
            if (referencedTableInfo != null) {
              constraint = new ConstraintMetadata(constraintName.toLowerCase(), constraintType,
                                                referencedOwner.toLowerCase(), referencedTableInfo[0].toLowerCase());
            } else {
              log.warn("Could not resolve referenced table for foreign key constraint {}.{}",
                      owner, constraintName);
              continue;
            }
          } else {
            constraint = new ConstraintMetadata(constraintName.toLowerCase(), constraintType);
          }

          // Set constraint properties
          constraint.setStatus(status);
          constraint.setDeferrable("DEFERRABLE".equals(deferrable));
          constraint.setInitiallyDeferred("DEFERRED".equals(deferred));
          constraint.setValidated("VALIDATED".equals(validated));
          constraint.setIndexName(indexName != null ? indexName.toLowerCase() : null);
          
          if ("R".equals(constraintType)) {
            constraint.setDeleteRule(deleteRule);
            // Note: Oracle doesn't have separate update rules in all_constraints
            // Update rules are typically the same as delete rules or NO ACTION
          }
          
          if ("C".equals(constraintType)) {
            constraint.setCheckCondition(searchCondition);
          }

          // Fetch columns for this constraint
          fetchConstraintColumns(oracleConn, owner, table, constraintName, constraint);
          
          // For foreign keys, fetch referenced columns
          if ("R".equals(constraintType) && referencedOwner != null && referencedConstraintName != null) {
            fetchReferencedColumns(oracleConn, referencedOwner, referencedConstraintName, constraint);
          }

          // Add constraint to table metadata if it's valid
          if (constraint.isValid()) {
            tableMetadata.addConstraint(constraint);
          } else {
            log.warn("Skipping invalid constraint: {}", constraint);
          }
        }
      }
    }
  }

  /**
   * Fetches columns for a specific constraint.
   */
  private static void fetchConstraintColumns(Connection oracleConn, String owner, String table, 
                                           String constraintName, ConstraintMetadata constraint) throws SQLException {
    String consColsSql = "SELECT column_name FROM all_cons_columns " +
            "WHERE owner = ? AND table_name = ? AND constraint_name = ? ORDER BY position";
    
    try (PreparedStatement psCols = oracleConn.prepareStatement(consColsSql)) {
      psCols.setString(1, owner.toUpperCase());
      psCols.setString(2, table);
      psCols.setString(3, constraintName);
      
      try (ResultSet rsCols = psCols.executeQuery()) {
        while (rsCols.next()) {
          constraint.addColumnName(rsCols.getString("column_name").toLowerCase());
        }
      }
    }
  }

  /**
   * Resolves the referenced table name and schema for a foreign key constraint.
   * 
   * @param oracleConn Oracle database connection
   * @param referencedOwner Schema of the referenced constraint
   * @param referencedConstraintName Name of the referenced constraint (typically primary key)
   * @return Array containing [tableName, schemaName] or null if not found
   */
  private static String[] resolveReferencedTable(Connection oracleConn, String referencedOwner, 
                                               String referencedConstraintName) throws SQLException {
    String sql = "SELECT table_name FROM all_constraints " +
            "WHERE owner = ? AND constraint_name = ?";
    
    try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
      ps.setString(1, referencedOwner.toUpperCase());
      ps.setString(2, referencedConstraintName);
      
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String tableName = rs.getString("table_name");
          return new String[]{tableName, referencedOwner};
        }
      }
    }
    return null;
  }

  /**
   * Fetches the referenced columns for a foreign key constraint.
   */
  private static void fetchReferencedColumns(Connection oracleConn, String referencedOwner, 
                                           String referencedConstraintName, ConstraintMetadata constraint) throws SQLException {
    String sql = "SELECT column_name FROM all_cons_columns " +
            "WHERE owner = ? AND constraint_name = ? ORDER BY position";
    
    try (PreparedStatement ps = oracleConn.prepareStatement(sql)) {
      ps.setString(1, referencedOwner.toUpperCase());
      ps.setString(2, referencedConstraintName);
      
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          constraint.addReferencedColumnName(rs.getString("column_name").toLowerCase());
        }
      }
    }
  }
}