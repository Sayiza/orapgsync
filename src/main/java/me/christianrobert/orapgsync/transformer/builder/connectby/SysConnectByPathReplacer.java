package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces SYS_CONNECT_BY_PATH function calls with generated column references.
 *
 * <p>Oracle's SYS_CONNECT_BY_PATH(column, separator) is a pseudo-function that builds
 * hierarchical paths. In PostgreSQL recursive CTEs, we generate explicit path columns.</p>
 *
 * <p>Transformation:</p>
 * <pre>
 * -- Oracle
 * SELECT SYS_CONNECT_BY_PATH(emp_name, '/') as path
 * FROM employees
 * CONNECT BY PRIOR emp_id = manager_id
 *
 * -- PostgreSQL (after CTE generation)
 * WITH RECURSIVE employees_hierarchy AS (
 *   SELECT emp_id, emp_name, '/' || emp_name as path_1, 1 as level
 *   FROM employees WHERE manager_id IS NULL
 *   UNION ALL
 *   SELECT t.emp_id, t.emp_name, h.path_1 || '/' || t.emp_name as path_1, h.level + 1
 *   FROM employees t JOIN employees_hierarchy h ON t.manager_id = h.emp_id
 * )
 * SELECT path_1 as path FROM employees_hierarchy  -- SYS_CONNECT_BY_PATH replaced with path_1
 * </pre>
 */
public class SysConnectByPathReplacer {

  /**
   * Replaces SYS_CONNECT_BY_PATH calls in SELECT list.
   *
   * @param ctx SELECT list context
   * @param pathColumns List of path column info for matching
   * @param b PostgresCodeBuilder for delegating transformations
   * @return Transformed SELECT list with SYS_CONNECT_BY_PATH → column references
   */
  public static String replaceInSelectList(
      PlSqlParser.Selected_listContext ctx,
      List<PathColumnInfo> pathColumns,
      PostgresCodeBuilder b) {

    if (ctx == null || pathColumns == null || pathColumns.isEmpty()) {
      return b.visit(ctx);  // No replacement needed
    }

    // Build deduplication map for fast lookup
    Map<String, String> pathReplacementMap = buildReplacementMap(pathColumns);

    // Visit normally, then do string-based replacement
    String selectList = b.visit(ctx);

    // Replace each SYS_CONNECT_BY_PATH call with its generated column name
    for (Map.Entry<String, String> entry : pathReplacementMap.entrySet()) {
      String pattern = entry.getKey();  // e.g., "SYS_CONNECT_BY_PATH(emp_name,'/') "
      String replacement = entry.getValue();  // e.g., "path_1"

      // Create regex pattern that matches function call (case-insensitive)
      // Pattern: SYS_CONNECT_BY_PATH(...) with balanced parentheses
      String regex = "SYS_CONNECT_BY_PATH\\s*\\(\\s*" +
                     escapeRegex(getColumnExpression(pattern)) +
                     "\\s*,\\s*" +
                     escapeRegex(getSeparator(pattern)) +
                     "\\s*\\)";

      selectList = selectList.replaceAll("(?i)" + regex, replacement);
    }

    return selectList;
  }

  /**
   * Replaces SYS_CONNECT_BY_PATH calls in WHERE clause.
   *
   * @param ctx WHERE clause context
   * @param pathColumns List of path column info for matching
   * @param b PostgresCodeBuilder
   * @return Transformed WHERE clause
   */
  public static String replaceInWhereClause(
      PlSqlParser.Where_clauseContext ctx,
      List<PathColumnInfo> pathColumns,
      PostgresCodeBuilder b) {

    if (ctx == null || pathColumns == null || pathColumns.isEmpty()) {
      if (ctx != null) {
        return b.visit(ctx);
      }
      return null;
    }

    // Build replacement map
    Map<String, String> pathReplacementMap = buildReplacementMap(pathColumns);

    // Visit normally
    String whereClause = b.visit(ctx);

    // Replace each SYS_CONNECT_BY_PATH call
    for (Map.Entry<String, String> entry : pathReplacementMap.entrySet()) {
      String pattern = entry.getKey();
      String replacement = entry.getValue();

      String regex = "SYS_CONNECT_BY_PATH\\s*\\(\\s*" +
                     escapeRegex(getColumnExpression(pattern)) +
                     "\\s*,\\s*" +
                     escapeRegex(getSeparator(pattern)) +
                     "\\s*\\)";

      whereClause = whereClause.replaceAll("(?i)" + regex, replacement);
    }

    return whereClause;
  }

  /**
   * Replaces SYS_CONNECT_BY_PATH calls in ORDER BY clause.
   *
   * @param ctx ORDER BY context
   * @param pathColumns List of path column info
   * @param b PostgresCodeBuilder
   * @return Transformed ORDER BY
   */
  public static String replaceInOrderBy(
      PlSqlParser.Order_by_clauseContext ctx,
      List<PathColumnInfo> pathColumns,
      PostgresCodeBuilder b) {

    if (ctx == null || pathColumns == null || pathColumns.isEmpty()) {
      if (ctx != null) {
        return b.visit(ctx);
      }
      return "";
    }

    // Build replacement map
    Map<String, String> pathReplacementMap = buildReplacementMap(pathColumns);

    // Visit normally
    String orderBy = b.visit(ctx);

    // Replace each SYS_CONNECT_BY_PATH call
    for (Map.Entry<String, String> entry : pathReplacementMap.entrySet()) {
      String pattern = entry.getKey();
      String replacement = entry.getValue();

      String regex = "SYS_CONNECT_BY_PATH\\s*\\(\\s*" +
                     escapeRegex(getColumnExpression(pattern)) +
                     "\\s*,\\s*" +
                     escapeRegex(getSeparator(pattern)) +
                     "\\s*\\)";

      orderBy = orderBy.replaceAll("(?i)" + regex, replacement);
    }

    return orderBy;
  }

  /**
   * Builds replacement map from path columns.
   * Key: deduplication pattern (expression|separator)
   * Value: generated column name
   */
  private static Map<String, String> buildReplacementMap(List<PathColumnInfo> pathColumns) {
    Map<String, String> map = new HashMap<>();
    for (PathColumnInfo pathCol : pathColumns) {
      String key = pathCol.getDeduplicationKey();
      String value = pathCol.getGeneratedColumnName();
      map.put(key, value);
    }
    return map;
  }

  /**
   * Extracts column expression from deduplication key.
   */
  private static String getColumnExpression(String deduplicationKey) {
    int separatorIndex = deduplicationKey.lastIndexOf('|');
    if (separatorIndex > 0) {
      return deduplicationKey.substring(0, separatorIndex);
    }
    return deduplicationKey;
  }

  /**
   * Extracts separator from deduplication key.
   */
  private static String getSeparator(String deduplicationKey) {
    int separatorIndex = deduplicationKey.lastIndexOf('|');
    if (separatorIndex > 0 && separatorIndex < deduplicationKey.length() - 1) {
      return deduplicationKey.substring(separatorIndex + 1);
    }
    return "";
  }

  /**
   * Escapes special regex characters in a string.
   */
  private static String escapeRegex(String str) {
    // Escape regex special characters
    return str.replaceAll("([\\\\\\[\\](){}+*?^$|.])", "\\\\$1");
  }
}
