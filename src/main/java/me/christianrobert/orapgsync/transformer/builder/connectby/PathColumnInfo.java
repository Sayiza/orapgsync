package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;

/**
 * Information about a SYS_CONNECT_BY_PATH column in a hierarchical query.
 *
 * <p>Oracle's SYS_CONNECT_BY_PATH(column, separator) builds a hierarchical path
 * by concatenating column values from root to current node with a separator.</p>
 *
 * <p>Example:</p>
 * <pre>
 * -- Oracle
 * SELECT SYS_CONNECT_BY_PATH(emp_name, '/') as path
 * FROM emp
 * CONNECT BY PRIOR emp_id = mgr;
 *
 * -- PostgreSQL (generated)
 * WITH RECURSIVE emp_hierarchy AS (
 *   SELECT emp_id, '/' || emp_name as path_1, 1 as level
 *   FROM emp WHERE mgr IS NULL
 *   UNION ALL
 *   SELECT t.emp_id, h.path_1 || '/' || t.emp_name as path_1, h.level + 1
 *   FROM emp t JOIN emp_hierarchy h ON t.mgr = h.emp_id
 * )
 * SELECT path_1 as path FROM emp_hierarchy;
 * </pre>
 *
 * <p>This class stores the information needed to generate the path column
 * in the CTE and replace function calls with column references.</p>
 */
public class PathColumnInfo {

  /** The column expression from SYS_CONNECT_BY_PATH first argument */
  private final PlSqlParser.ExpressionContext columnExpression;

  /** The separator string (e.g., "/", ">", " -> ") */
  private final String separator;

  /** Generated column name in the CTE (e.g., "path_1", "path_2") */
  private final String generatedColumnName;

  /** Original expression text (for matching and debugging) */
  private final String expressionText;

  public PathColumnInfo(
      PlSqlParser.ExpressionContext columnExpression,
      String separator,
      String generatedColumnName,
      String expressionText) {
    this.columnExpression = columnExpression;
    this.separator = separator;
    this.generatedColumnName = generatedColumnName;
    this.expressionText = expressionText;
  }

  /**
   * Builds the path column for the base case (root nodes).
   *
   * <p>Pattern: {@code separator || transformed_expression as generated_column_name}</p>
   *
   * <p>Example: {@code '/' || emp_name as path_1}</p>
   *
   * @param b PostgresCodeBuilder for transforming the column expression
   * @return SQL fragment for base case path column
   */
  public String buildBaseCase(PostgresCodeBuilder b) {
    String transformedExpression = b.visit(columnExpression);
    return separator + " || " + transformedExpression + " AS " + generatedColumnName;
  }

  /**
   * Builds the path column for the recursive case (child nodes).
   *
   * <p>Pattern: {@code cte_alias.generated_column_name || separator || transformed_expression as generated_column_name}</p>
   *
   * <p>Example: {@code h.path_1 || '/' || t.emp_name as path_1}</p>
   *
   * @param b PostgresCodeBuilder for transforming the column expression
   * @param cteAlias Alias for the CTE in the recursive JOIN (e.g., "h")
   * @return SQL fragment for recursive case path column
   */
  public String buildRecursiveCase(PostgresCodeBuilder b, String cteAlias) {
    String transformedExpression = b.visit(columnExpression);
    return cteAlias + "." + generatedColumnName + " || " + separator + " || " +
           transformedExpression + " AS " + generatedColumnName;
  }

  // Getters

  public PlSqlParser.ExpressionContext getColumnExpression() {
    return columnExpression;
  }

  public String getSeparator() {
    return separator;
  }

  public String getGeneratedColumnName() {
    return generatedColumnName;
  }

  public String getExpressionText() {
    return expressionText;
  }

  /**
   * Creates a unique key for deduplication.
   *
   * <p>Two SYS_CONNECT_BY_PATH calls with the same expression and separator
   * can share the same generated column.</p>
   *
   * @return Key for deduplication (expressionText + separator)
   */
  public String getDeduplicationKey() {
    return expressionText + "|" + separator;
  }

  @Override
  public String toString() {
    return "PathColumnInfo{" +
           "expression='" + expressionText + '\'' +
           ", separator='" + separator + '\'' +
           ", generatedColumn='" + generatedColumnName + '\'' +
           '}';
  }
}
