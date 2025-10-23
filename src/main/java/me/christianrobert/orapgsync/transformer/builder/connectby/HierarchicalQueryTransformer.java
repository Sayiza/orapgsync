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

        // Replace LEVEL with 1 in base case (base case is always level 1)
        // This handles conditions like WHERE LEVEL <= 3 (always true in base case)
        // Use case-insensitive regex to handle all variations in one pass
        originalWhere = originalWhere.replaceAll("(?i)\\bLEVEL\\b", "1");

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
    // Pass cteAlias for LEVEL replacement (LEVEL → h.level + 1)
    String whereClause = buildRecursiveCaseWhere(ctx, childAlias, cteAlias, b);
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
   * <p>Special handling for LEVEL: LEVEL references are replaced with "h.level + 1"
   * to represent the depth of the next level being added.</p>
   * <p>Column references are qualified with table alias to avoid ambiguity in JOIN.</p>
   */
  private static String buildRecursiveCaseWhere(
      PlSqlParser.Query_blockContext ctx,
      String childAlias,
      String cteAlias,
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

    // Replace LEVEL references with h.level + 1 (representing the next level being added)
    // This handles depth limiting: WHERE LEVEL <= 3 becomes WHERE h.level + 1 <= 3
    // Use case-insensitive regex to handle all variations in one pass (avoids double replacement)
    whereClause = whereClause.replaceAll("(?i)\\bLEVEL\\b", cteAlias + ".level + 1");

    // Qualify column references with child alias to avoid ambiguity
    whereClause = qualifyWhereClauseColumns(whereClause, childAlias, cteAlias);

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
      // Replace LEVEL references in ORDER BY
      String orderByClause = LevelReferenceReplacer.replaceInOrderBy(orderByCtx, b);
      if (orderByClause != null && !orderByClause.trim().isEmpty()) {
        result.append(" ").append(orderByClause);
      }
    }

    return result.toString();
  }

  /**
   * Builds SELECT list with level column added.
   *
   * <p>Removes any LEVEL pseudo-column references from original SELECT list,
   * then adds ", {levelExpression} as level".</p>
   *
   * <p>This is necessary because Oracle's LEVEL is a pseudo-column that doesn't
   * exist in the base table. In PostgreSQL CTEs, we explicitly add it as a column.</p>
   */
  private static String buildSelectListWithLevel(
      PlSqlParser.Query_blockContext ctx,
      String levelExpression,
      PostgresCodeBuilder b) {

    PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
    if (selectedListCtx == null) {
      throw new TransformationException("Query missing SELECT list");
    }

    // Visit SELECT list to get transformed columns
    String selectList = b.visit(selectedListCtx);

    // CRITICAL: Remove any LEVEL pseudo-column references from SELECT list
    // Oracle allows "SELECT emp_id, LEVEL FROM ..." but PostgreSQL doesn't have LEVEL
    // We'll add it explicitly as a calculated column
    selectList = removeLevelFromSelectList(selectList);

    // Add level column as calculated value
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

    // Use AST-based LEVEL replacement (more robust than regex)
    return LevelReferenceReplacer.replaceInSelectList(selectedListCtx, b);
  }

  /**
   * Qualifies column references in SELECT list with table alias.
   *
   * <p>This is critical in the recursive case to avoid ambiguity when the base table
   * is joined with the CTE. Without qualification, PostgreSQL reports "column reference is ambiguous".</p>
   *
   * <p><b>Strategy</b>:</p>
   * <ul>
   *   <li>Split SELECT list by commas</li>
   *   <li>For each item, qualify if it's a simple column reference</li>
   *   <li>Skip items that are already qualified (contain dot)</li>
   *   <li>Skip the level expression (contains "as level")</li>
   *   <li>Handle column aliases (e.g., "emp_id AS id" → "t.emp_id AS id")</li>
   * </ul>
   *
   * <p><b>Examples</b>:</p>
   * <ul>
   *   <li>"emp_id" → "t.emp_id"</li>
   *   <li>"emp_id, manager_id" → "t.emp_id, t.manager_id"</li>
   *   <li>"e.emp_id" → "e.emp_id" (already qualified)</li>
   *   <li>"h.level + 1 as level" → "h.level + 1 as level" (level expression)</li>
   *   <li>"emp_id AS id" → "t.emp_id AS id"</li>
   * </ul>
   *
   * @param selectList The SELECT list string (comma-separated columns)
   * @param alias The table alias to use for qualification (e.g., "t")
   * @param components CONNECT BY components (unused currently, for future enhancements)
   * @return SELECT list with unqualified columns qualified
   */
  private static String qualifySelectListColumns(
      String selectList,
      String alias,
      ConnectByComponents components) {

    if (selectList == null || selectList.trim().isEmpty()) {
      return selectList;
    }

    // Split by comma to get individual SELECT items
    String[] items = selectList.split(",");
    StringBuilder result = new StringBuilder();

    for (String item : items) {
      String trimmed = item.trim();

      // Skip the level expression (e.g., "h.level + 1 as level")
      // This is our generated column, not from the base table
      if (trimmed.matches("(?i).*\\bas\\s+level\\b.*")) {
        if (result.length() > 0) {
          result.append(", ");
        }
        result.append(trimmed);
        continue;
      }

      // Skip already qualified columns (contain dot)
      // E.g., "e.emp_id" or "h.level"
      if (trimmed.contains(".")) {
        if (result.length() > 0) {
          result.append(", ");
        }
        result.append(trimmed);
        continue;
      }

      // Qualify simple column references
      // Handle both "column_name" and "column_name AS alias"
      String qualified;
      if (trimmed.matches("(?i)^\\w+\\s+AS\\s+\\w+$")) {
        // Pattern: "column_name AS alias_name"
        // Split and qualify the column part
        String[] parts = trimmed.split("(?i)\\s+AS\\s+");
        qualified = alias + "." + parts[0].trim() + " AS " + parts[1].trim();
      } else if (trimmed.matches("^\\w+$")) {
        // Simple column name (just word characters)
        qualified = alias + "." + trimmed;
      } else {
        // Complex expression or function - leave as is
        // This handles cases like "UPPER(name)", "salary * 2", etc.
        qualified = trimmed;
      }

      if (result.length() > 0) {
        result.append(", ");
      }
      result.append(qualified);
    }

    return result.toString();
  }

  /**
   * Qualifies column references in WHERE clause with table alias.
   *
   * <p>This is critical in the recursive case to avoid ambiguity when the base table
   * is joined with the CTE. Without qualification, PostgreSQL reports "column reference is ambiguous".</p>
   *
   * <p><b>Strategy</b>:</p>
   * <ul>
   *   <li>Extract and preserve string literals (to avoid qualifying content inside quotes)</li>
   *   <li>Find unqualified identifiers (word boundaries not preceded by dot)</li>
   *   <li>Skip SQL keywords (AND, OR, NOT, IS, NULL, TRUE, FALSE, etc.)</li>
   *   <li>Skip already qualified columns (contain dot, e.g., h.level)</li>
   *   <li>Qualify remaining identifiers as table columns</li>
   *   <li>Restore string literals</li>
   * </ul>
   *
   * <p><b>Examples</b>:</p>
   * <ul>
   *   <li>"salary > 50000" → "t.salary > 50000"</li>
   *   <li>"dept = 'Sales'" → "t.dept = 'Sales'"</li>
   *   <li>"salary > 50000 AND dept = 'Sales'" → "t.salary > 50000 AND t.dept = 'Sales'"</li>
   *   <li>"h.level + 1 <= 3" → "h.level + 1 <= 3" (already qualified)</li>
   *   <li>"(dept = 'Engineering' AND salary > 90000) OR dept = 'Executive'" →
   *       "(t.dept = 'Engineering' AND t.salary > 90000) OR t.dept = 'Executive'"</li>
   * </ul>
   *
   * @param whereClause The WHERE clause condition string
   * @param alias The table alias to use for qualification (e.g., "t")
   * @param cteAlias The CTE alias (e.g., "h") - used to detect already-qualified CTE columns
   * @return WHERE clause with unqualified columns qualified
   */
  private static String qualifyWhereClauseColumns(
      String whereClause,
      String alias,
      String cteAlias) {

    if (whereClause == null || whereClause.trim().isEmpty()) {
      return whereClause;
    }

    // Step 1: Extract string literals and replace with placeholders
    java.util.List<String> stringLiterals = new java.util.ArrayList<>();
    java.util.regex.Pattern stringPattern = java.util.regex.Pattern.compile("'([^']*(?:''[^']*)*)'");
    java.util.regex.Matcher stringMatcher = stringPattern.matcher(whereClause);
    StringBuilder withoutStrings = new StringBuilder();
    int lastIdx = 0;
    int placeholderIndex = 0;

    while (stringMatcher.find()) {
      withoutStrings.append(whereClause, lastIdx, stringMatcher.start());
      String placeholder = "__STR_LITERAL_" + placeholderIndex + "__";
      withoutStrings.append(placeholder);
      stringLiterals.add(stringMatcher.group(0)); // Store entire match including quotes
      placeholderIndex++;
      lastIdx = stringMatcher.end();
    }
    withoutStrings.append(whereClause.substring(lastIdx));

    String processedClause = withoutStrings.toString();

    // Step 2: SQL keywords that should not be qualified
    java.util.Set<String> SQL_KEYWORDS = java.util.Set.of(
        "AND", "OR", "NOT", "IS", "NULL", "TRUE", "FALSE",
        "IN", "BETWEEN", "LIKE", "EXISTS", "ANY", "ALL", "SOME",
        "ASC", "DESC"
    );

    // Step 3: Find and qualify column identifiers
    StringBuilder result = new StringBuilder();
    int lastIndex = 0;

    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");
    java.util.regex.Matcher matcher = pattern.matcher(processedClause);

    while (matcher.find()) {
      String identifier = matcher.group(1);
      int startPos = matcher.start();

      // Check if preceded by a dot (already qualified)
      boolean isAlreadyQualified = false;
      if (startPos > 0 && processedClause.charAt(startPos - 1) == '.') {
        isAlreadyQualified = true;
      }

      // Check if followed by a dot (is a qualifier itself, like "t" in "t.column")
      boolean isQualifier = false;
      int endPos = matcher.end();
      if (endPos < processedClause.length() && processedClause.charAt(endPos) == '.') {
        isQualifier = true;
      }

      // Check if it's a placeholder
      boolean isPlaceholder = identifier.equals("__STR_LITERAL_") || identifier.startsWith("__STR");

      // Append everything before this match
      result.append(processedClause, lastIndex, startPos);

      // Decide whether to qualify
      if (isAlreadyQualified || isQualifier || isPlaceholder || SQL_KEYWORDS.contains(identifier.toUpperCase())) {
        // Keep as is
        result.append(identifier);
      } else {
        // Qualify with table alias
        result.append(alias).append(".").append(identifier);
      }

      lastIndex = endPos;
    }

    // Append any remaining text
    if (lastIndex < processedClause.length()) {
      result.append(processedClause.substring(lastIndex));
    }

    // Step 4: Restore string literals
    String finalResult = result.toString();
    for (int i = 0; i < stringLiterals.size(); i++) {
      String placeholder = "__STR_LITERAL_" + i + "__";
      finalResult = finalResult.replace(placeholder, stringLiterals.get(i));
    }

    return finalResult;
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

  /**
   * Removes LEVEL pseudo-column references from SELECT list.
   *
   * <p>Handles patterns like:</p>
   * <ul>
   *   <li>"emp_id, LEVEL" → "emp_id"</li>
   *   <li>"emp_id, LEVEL, name" → "emp_id, name"</li>
   *   <li>"emp_id, LEVEL AS lvl" → "emp_id"</li>
   *   <li>"emp_id, LEVEL as hierarchy_level" → "emp_id"</li>
   *   <li>"LEVEL, emp_id" → "emp_id"</li>
   *   <li>"LEVEL * 2 AS depth, emp_id" → "emp_id" (removes entire expression)</li>
   * </ul>
   *
   * <p><b>Strategy</b>: Remove comma-separated items that contain LEVEL keyword.</p>
   *
   * @param selectList Original SELECT list string
   * @return SELECT list with LEVEL references removed
   */
  private static String removeLevelFromSelectList(String selectList) {
    if (selectList == null || selectList.trim().isEmpty()) {
      return selectList;
    }

    // Split by comma to get individual select items
    String[] items = selectList.split(",");
    StringBuilder result = new StringBuilder();

    for (String item : items) {
      String trimmed = item.trim();

      // Skip items that reference LEVEL pseudo-column (case-insensitive)
      // Match whole word LEVEL, not part of other identifiers
      if (trimmed.matches("(?i).*\\bLEVEL\\b.*")) {
        continue; // Skip this item
      }

      // Add non-LEVEL item to result
      if (result.length() > 0) {
        result.append(" , ");
      }
      result.append(trimmed);
    }

    return result.toString();
  }
}
