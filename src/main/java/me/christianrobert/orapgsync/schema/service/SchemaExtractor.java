package me.christianrobert.orapgsync.schema.service;

import me.christianrobert.orapgsync.table.tools.UserExcluder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import me.christianrobert.orapgsync.core.tools.PostgreSqlIdentifierUtils;

public class SchemaExtractor {

  private static final Logger log = LoggerFactory.getLogger(SchemaExtractor.class);

  public static List<String> fetchUsernames(Connection oracleConn) throws SQLException {
    List<String> result = new ArrayList<>();

    String sql = "SELECT username FROM all_users ORDER BY username";
    try (Statement stmt = oracleConn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {

      while (rs.next()) {
        String username = rs.getString("username");
        if (!UserExcluder.is2BeExclueded(username)) {
          result.add(username);
        }
      }
    }
    log.info("Extracted {} schemas from database", result.size());
    return result;
  }

  /**
   * Generates PostgreSQL CREATE SCHEMA statements for the given list of schema names.
   * Excludes schemas based on UserExcluder logic.
   * Returns statements in a single string, separated by semicolon and two newlines.
   *
   * @param schemaNames List of schema names (Oracle users)
   * @return String containing CREATE SCHEMA statements
   */
  public static String toPostgre(List<String> schemaNames) {
    List<String> statements = new ArrayList<>();

    for (String schema : schemaNames) {
      // Skip excluded users
      if (UserExcluder.is2BeExclueded(schema)) {
        continue;
      }

      // Validate schema name
      if (!isValidSchemaName(schema)) {
        log.warn("Skipping invalid schema name: {}", schema);
        continue;
      }

      // Generate CREATE SCHEMA statement
      String statement = "CREATE SCHEMA IF NOT EXISTS " + schema;
      statements.add(statement);
    }

    // Join statements with semicolon and two newlines
    return String.join(";\n\n", statements) + (statements.isEmpty() ? "" : ";");
  }

  /**
   * Validates if a schema name is suitable for PostgreSQL.
   * Checks for null, empty, length, and invalid characters.
   */
  private static boolean isValidSchemaName(String schema) {
    if (schema == null || schema.trim().isEmpty()) {
      return false;
    }
    // PostgreSQL identifier length limit is 63 characters
    if (schema.length() > 63) {
      return false;
    }
    // Check for valid characters (alphanumeric, underscore, or needs quoting)
    return schema.matches("[a-zA-Z0-9_]+") || !PostgreSqlIdentifierUtils.isPostgresReservedWord(schema);
  }

}
