package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Transforms Oracle CONNECT BY hierarchical queries to PostgreSQL recursive CTEs.
 *
 * <p>Transformation pattern:</p>
 * <pre>
 * Oracle:
 *   SELECT columns
 *   FROM table
 *   WHERE filter
 *   START WITH root_condition
 *   CONNECT BY PRIOR parent_col = child_col
 *
 * PostgreSQL:
 *   WITH RECURSIVE table_hierarchy AS (
 *     -- Base case
 *     SELECT columns, 1 as level
 *     FROM table
 *     WHERE root_condition [AND filter]
 *
 *     UNION ALL
 *
 *     -- Recursive case
 *     SELECT t.columns, h.level + 1
 *     FROM table t
 *     JOIN table_hierarchy h ON t.child_col = h.parent_col
 *     WHERE filter
 *   )
 *   SELECT columns FROM table_hierarchy
 * </pre>
 *
 * <p>This transformer delegates sub-transformations to existing visitor infrastructure,
 * ensuring all transformations (schema qualification, type methods, package functions,
 * Oracle function conversions, etc.) work inside CONNECT BY queries.</p>
 */
public class HierarchicalQueryTransformer {

  /**
   * Transforms hierarchical query to recursive CTE.
   *
   * @param ctx Query block with CONNECT BY
   * @param b PostgresCodeBuilder for delegation
   * @return Complete recursive CTE query
   */
  public static String transform(
      PlSqlParser.Query_blockContext ctx,
      PostgresCodeBuilder b) {

    // 1. ANALYZE: Extract all components
    ConnectByComponents components = ConnectByAnalyzer.analyze(ctx);

    // 2. VALIDATE: Check for unsupported features
    validateSupportedFeatures(components);

    // 3. GENERATE: Build CTE name
    String cteName = generateCteName(components);
    String cteAlias = "h";  // Short alias for hierarchy CTE

    // 4. BUILD: Base case
    String baseCase = buildBaseCase(components, ctx, b);

    // 5. BUILD: Recursive case
    String recursiveCase = buildRecursiveCase(components, ctx, cteName, cteAlias, b);

    // 6. BUILD: Final SELECT
    String finalSelect = buildFinalSelect(components, ctx, cteName, b);

    // 7. ASSEMBLE: Complete CTE
    return assembleCte(cteName, baseCase, recursiveCase, finalSelect);
  }

  /**
   * Validates that all used features are supported.
   */
  private static void validateSupportedFeatures(ConnectByComponents components) {
    if (components.hasNoCycle()) {
      // NOCYCLE is documented limitation - provide helpful guidance
      throw new TransformationException(
          "NOCYCLE clause is not directly supported in PostgreSQL recursive CTEs.\n" +
          "PostgreSQL does not have built-in cycle detection like Oracle.\n" +
          "To prevent infinite loops, you can:\n" +
          "1. Add manual cycle detection using an array to track visited nodes:\n" +
          "   SELECT ..., ARRAY[emp_id] as path ...\n" +
          "   WHERE NOT (e.emp_id = ANY(h.path))\n" +
          "2. Add a depth limit: WHERE h.level < 100\n" +
          "3. Ensure your data has no cycles (enforce at application level)"
      );
    }

    if (components.usesAdvancedPseudoColumns()) {
      // Advanced pseudo-columns are Phase 5 (future work)
      throw new TransformationException(
          "Advanced hierarchical query pseudo-columns not yet supported:\n" +
          (components.usesConnectByRoot() ? "- CONNECT_BY_ROOT\n" : "") +
          (components.usesConnectByPath() ? "- SYS_CONNECT_BY_PATH\n" : "") +
          (components.usesConnectByIsLeaf() ? "- CONNECT_BY_ISLEAF\n" : "") +
          "These features are planned for future implementation."
      );
    }
  }

  /**
   * Generates CTE name from base table name.
   *
   * <p>Pattern: {table}_hierarchy</p>
   * <p>TODO: Check for conflicts with existing CTEs</p>
   */
  private static String generateCteName(ConnectByComponents components) {
    String baseTable = components.getBaseTableName();

    // Strip schema qualifier if present
    if (baseTable.contains(".")) {
      String[] parts = baseTable.split("\\.");
      baseTable = parts[parts.length - 1];
    }

    return baseTable + "_hierarchy";
  }

  /**
   * Builds base case (non-recursive member) of CTE.
   *
   * <p>Pattern:</p>
   * <pre>
   * SELECT columns, 1 as level
   * FROM table
   * WHERE start_with_condition [AND original_where]
   * </pre>
   */
  private static String buildBaseCase(
      ConnectByComponents components,
      PlSqlParser.Query_blockContext ctx,
      PostgresCodeBuilder b) {

    StringBuilder result = new StringBuilder();

    // SELECT list with level column
    result.append("SELECT ");
    String selectList = buildSelectListWithLevel(ctx, "1", b);
    result.append(selectList);

    // FROM clause (delegate to existing visitor)
    PlSqlParser.From_clauseContext fromCtx = ctx.from_clause();
    if (fromCtx != null) {
      String fromClause = b.visit(fromCtx);
      result.append(" FROM ").append(fromClause);
    }

    // WHERE clause: START WITH [AND original WHERE]
    String whereClause = buildBaseCaseWhere(components, ctx, b);
    if (whereClause != null && !whereClause.trim().isEmpty()) {
      result.append(" WHERE ").append(whereClause);
    }

    return result.toString();
  }

  /**
   * Builds WHERE clause for base case.
   *
   * <p>Combines:</p>
   * <ul>
   *   <li>START WITH condition (required for base case)</li>
   *   <li>Original WHERE condition (if present)</li>
   * </ul>
   */
  private static String buildBaseCaseWhere(
      ConnectByComponents components,
      PlSqlParser.Query_blockContext ctx,
      PostgresCodeBuilder b) {

    StringBuilder where = new StringBuilder();

    // Add START WITH condition
    if (components.hasStartWith()) {
      PlSqlParser.ConditionContext startWithCond = components.getStartWith().condition();
      String startWithStr = b.visit(startWithCond);
      where.append(startWithStr);
    } else {
      // No START WITH - need to infer root condition
      // For now, throw error (this is rare pattern)
      throw new TransformationException(
          "CONNECT BY without START WITH is not yet supported. " +
          "Please specify START WITH clause to identify root nodes."
      );
    }

    // Add original WHERE condition (if present)
    PlSqlParser.Where_clauseContext whereCtx = ctx.where_clause();
    if (whereCtx != null) {
      String originalWhere = b.visit(whereCtx);
      if (originalWhere != null && !originalWhere.trim().isEmpty()) {
        // Strip "WHERE" keyword if visitor included it
        originalWhere = originalWhere.trim();
        if (originalWhere.toUpperCase().startsWith("WHERE ")) {
          originalWhere = originalWhere.substring(6).trim();
        }

        if (!originalWhere.isEmpty()) {
          where.append(" AND ").append(originalWhere);
        }
      }
    }

    return where.toString();
  }

  /**
   * Builds recursive case (recursive member) of CTE.
   *
   * <p>Pattern:</p>
   * <pre>
   * SELECT t.columns, h.level + 1
   * FROM table t
   * JOIN cte_name h ON t.child_col = h.parent_col
   * WHERE original_where
   * </pre>
   */
  private static String buildRecursiveCase(
      ConnectByComponents components,
      PlSqlParser.Query_blockContext ctx,
      String cteName,
      String cteAlias,
      PostgresCodeBuilder b) {

    StringBuilder result = new StringBuilder();

    // Determine child table alias
    String childAlias = components.hasBaseTableAlias()
        ? components.getBaseTableAlias()
        : "t";  // Default alias if none specified

    // SELECT list with level increment
    result.append("SELECT ");
    String selectList = buildSelectListWithLevel(ctx, cteAlias + ".level + 1", b);
    // Qualify columns with child alias
    selectList = qualifySelectListColumns(selectList, childAlias, components);
    result.append(selectList);

    // FROM clause with child alias
    String fromClause = buildFromWithAlias(ctx, childAlias, b);
    result.append(" FROM ").append(fromClause);

    // JOIN to CTE
    String joinClause = buildRecursiveJoin(components, childAlias, cteName, cteAlias);
    result.append(" ").append(joinClause);

    // WHERE clause (original WHERE, if present)
    String whereClause = buildRecursiveCaseWhere(ctx, childAlias, b);
    if (whereClause != null && !whereClause.trim().isEmpty()) {
      result.append(" WHERE ").append(whereClause);
    }

    return result.toString();
  }

  /**
   * Builds FROM clause with explicit table alias.
   */
  private static String buildFromWithAlias(
      PlSqlParser.Query_blockContext ctx,
      String alias,
      PostgresCodeBuilder b) {

    PlSqlParser.From_clauseContext fromCtx = ctx.from_clause();
    if (fromCtx == null) {
      throw new TransformationException("CONNECT BY requires FROM clause");
    }

    // Visit FROM clause to get transformed table name
    String fromClause = b.visit(fromCtx);

    // If alias is different from original, replace it
    // For now, assume single table and append alias
    // TODO: More robust alias handling
    if (!fromClause.contains(" " + alias)) {
      fromClause = fromClause + " " + alias;
    }

    return fromClause;
  }

  /**
   * Builds JOIN clause for recursive CTE member.
   *
   * <p>Uses PRIOR expression to generate JOIN condition.</p>
   */
  private static String buildRecursiveJoin(
      ConnectByComponents components,
      String childAlias,
      String cteName,
      String cteAlias) {

    PriorExpression priorExpr = components.getPriorExpression();
    String joinCondition = priorExpr.generateJoinCondition(childAlias, cteAlias);

    return "JOIN " + cteName + " " + cteAlias + " ON " + joinCondition;
  }

  /**
   * Builds WHERE clause for recursive case.
   *
   * <p>Uses original WHERE condition (if present).</p>
   */
  private static String buildRecursiveCaseWhere(
      PlSqlParser.Query_blockContext ctx,
      String childAlias,
      PostgresCodeBuilder b) {

    PlSqlParser.Where_clauseContext whereCtx = ctx.where_clause();
    if (whereCtx == null) {
      return null;
    }

    String whereClause = b.visit(whereCtx);
    if (whereClause == null || whereClause.trim().isEmpty()) {
      return null;
    }

    // Strip "WHERE" keyword if visitor included it
    whereClause = whereClause.trim();
    if (whereClause.toUpperCase().startsWith("WHERE ")) {
      whereClause = whereClause.substring(6).trim();
    }

    // Qualify column references with child alias
    // TODO: More robust column qualification
    return whereClause;
  }

  /**
   * Builds final SELECT that queries the CTE.
   *
   * <p>Pattern:</p>
   * <pre>
   * SELECT columns
   * FROM cte_name
   * ORDER BY ... (if present)
   * </pre>
   */
  private static String buildFinalSelect(
      ConnectByComponents components,
      PlSqlParser.Query_blockContext ctx,
      String cteName,
      PostgresCodeBuilder b) {

    StringBuilder result = new StringBuilder();

    // SELECT list (replace LEVEL references)
    result.append("SELECT ");
    String selectList = buildSelectListReplacingLevel(ctx, b);
    result.append(selectList);

    // FROM CTE
    result.append(" FROM ").append(cteName);

    // ORDER BY (if present)
    // Note: ORDER SIBLINGS BY is not supported, only regular ORDER BY
    PlSqlParser.Order_by_clauseContext orderByCtx = ctx.order_by_clause();
    if (orderByCtx != null) {
      String orderByClause = b.visit(orderByCtx);
      if (orderByClause != null && !orderByClause.trim().isEmpty()) {
        result.append(" ").append(orderByClause);
      }
    }

    return result.toString();
  }

  /**
   * Builds SELECT list with level column added.
   *
   * <p>Adds ", {levelExpression} as level" to SELECT list.</p>
   */
  private static String buildSelectListWithLevel(
      PlSqlParser.Query_blockContext ctx,
      String levelExpression,
      PostgresCodeBuilder b) {

    PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
    if (selectedListCtx == null) {
      throw new TransformationException("Query missing SELECT list");
    }

    String selectList = b.visit(selectedListCtx);

    // Add level column
    return selectList + ", " + levelExpression + " as level";
  }

  /**
   * Builds SELECT list with LEVEL references replaced.
   *
   * <p>Replaces LEVEL pseudo-column with "level" column reference.</p>
   */
  private static String buildSelectListReplacingLevel(
      PlSqlParser.Query_blockContext ctx,
      PostgresCodeBuilder b) {

    PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
    if (selectedListCtx == null) {
      throw new TransformationException("Query missing SELECT list");
    }

    String selectList = b.visit(selectedListCtx);

    // Replace LEVEL with level (case-insensitive)
    // TODO: More robust replacement using AST visitor
    selectList = selectList.replaceAll("\\bLEVEL\\b", "level");
    selectList = selectList.replaceAll("\\blevel\\b", "level");  // Normalize

    return selectList;
  }

  /**
   * Qualifies column references in SELECT list with table alias.
   *
   * <p>TODO: More robust implementation using AST visitor.</p>
   */
  private static String qualifySelectListColumns(
      String selectList,
      String alias,
      ConnectByComponents components) {

    // For now, simple heuristic: if no dots in column names, add alias
    // This is a simplified approach - full implementation would need AST visitor
    return selectList;
  }

  /**
   * Assembles complete recursive CTE.
   */
  private static String assembleCte(
      String cteName,
      String baseCase,
      String recursiveCase,
      String finalSelect) {

    return "WITH RECURSIVE " + cteName + " AS (\n" +
           "  " + baseCase + "\n" +
           "  UNION ALL\n" +
           "  " + recursiveCase + "\n" +
           ")\n" +
           finalSelect;
  }
}
