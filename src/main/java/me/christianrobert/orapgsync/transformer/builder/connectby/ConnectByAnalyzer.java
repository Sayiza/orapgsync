package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes Oracle hierarchical query (CONNECT BY) to extract all components
 * needed for transformation to PostgreSQL recursive CTE.
 *
 * <p>This is the main entry point for CONNECT BY analysis.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * ConnectByComponents components = ConnectByAnalyzer.analyze(queryBlockCtx);
 * // Use components to generate recursive CTE
 * </pre>
 */
public class ConnectByAnalyzer {

  /**
   * Analyzes query block with hierarchical query clause.
   *
   * @param ctx Query block context containing CONNECT BY
   * @return Analyzed components
   * @throws TransformationException if analysis fails or unsupported pattern
   */
  public static ConnectByComponents analyze(PlSqlParser.Query_blockContext ctx) {
    if (ctx == null) {
      throw new TransformationException("Query block context is null");
    }

    // Extract hierarchical query clause
    List<PlSqlParser.Hierarchical_query_clauseContext> hierClauses =
        ctx.hierarchical_query_clause();

    if (hierClauses == null || hierClauses.isEmpty()) {
      throw new TransformationException("No hierarchical query clause found");
    }

    if (hierClauses.size() > 1) {
      throw new TransformationException(
          "Multiple hierarchical query clauses not supported. " +
          "Found " + hierClauses.size() + " clauses."
      );
    }

    PlSqlParser.Hierarchical_query_clauseContext hierClause = hierClauses.get(0);

    // Extract START WITH (optional)
    PlSqlParser.Start_partContext startWith = hierClause.start_part();

    // Extract CONNECT BY condition (required)
    PlSqlParser.ConditionContext connectByCondition = hierClause.condition();
    if (connectByCondition == null) {
      throw new TransformationException("CONNECT BY clause missing condition");
    }

    // Extract NOCYCLE flag
    boolean hasNoCycle = hierClause.NOCYCLE() != null;

    // Extract table information from FROM clause
    TableInfo tableInfo = extractTableInfo(ctx.from_clause());

    // Analyze PRIOR expression
    PriorExpression priorExpression = PriorExpressionAnalyzer.analyze(connectByCondition);

    // Scan for pseudo-column usage
    PseudoColumnUsage pseudoColumnUsage = scanPseudoColumnUsage(ctx);

    // Detect SYS_CONNECT_BY_PATH columns
    List<PathColumnInfo> pathColumns = detectPathColumns(ctx);

    // Build and return components
    return ConnectByComponents.builder()
        .queryBlockContext(ctx)
        .startWith(startWith)
        .connectByCondition(connectByCondition)
        .hasNoCycle(hasNoCycle)
        .baseTableName(tableInfo.tableName)
        .baseTableAlias(tableInfo.tableAlias)
        .priorExpression(priorExpression)
        .usesLevelInSelect(pseudoColumnUsage.usesLevelInSelect)
        .usesLevelInWhere(pseudoColumnUsage.usesLevelInWhere)
        .levelReferencePaths(pseudoColumnUsage.levelReferencePaths)
        .pathColumns(pathColumns)
        .usesConnectByRoot(pseudoColumnUsage.usesConnectByRoot)
        .usesConnectByPath(pseudoColumnUsage.usesConnectByPath)
        .usesConnectByIsLeaf(pseudoColumnUsage.usesConnectByIsLeaf)
        .build();
  }

  /**
   * Extracts table name and alias from FROM clause.
   *
   * <p>Currently supports single table only. Multi-table CONNECT BY
   * is rare and complex.</p>
   */
  private static TableInfo extractTableInfo(PlSqlParser.From_clauseContext fromClause) {
    if (fromClause == null) {
      throw new TransformationException("CONNECT BY requires FROM clause");
    }

    PlSqlParser.Table_ref_listContext tableRefList = fromClause.table_ref_list();
    if (tableRefList == null) {
      throw new TransformationException("FROM clause has no table references");
    }

    List<PlSqlParser.Table_refContext> tableRefs = tableRefList.table_ref();
    if (tableRefs == null || tableRefs.isEmpty()) {
      throw new TransformationException("FROM clause has no table references");
    }

    if (tableRefs.size() > 1) {
      throw new TransformationException(
          "CONNECT BY with multiple tables not yet supported. " +
          "Found " + tableRefs.size() + " tables. " +
          "Use single table with CONNECT BY."
      );
    }

    // Extract table name and alias from single table reference
    PlSqlParser.Table_refContext tableRef = tableRefs.get(0);
    return extractTableNameAndAlias(tableRef);
  }

  /**
   * Extracts table name and optional alias from table_ref.
   */
  private static TableInfo extractTableNameAndAlias(PlSqlParser.Table_refContext tableRef) {
    PlSqlParser.Table_ref_auxContext tableRefAux = tableRef.table_ref_aux();
    if (tableRefAux == null) {
      throw new TransformationException("Invalid table reference structure");
    }

    PlSqlParser.Table_ref_aux_internalContext internal = tableRefAux.table_ref_aux_internal();
    if (internal == null) {
      throw new TransformationException("Invalid table reference structure");
    }

    // Check if it's a simple table reference (not a subquery)
    if (!(internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext)) {
      throw new TransformationException(
          "CONNECT BY with subquery in FROM not yet supported. " +
          "Use simple table reference."
      );
    }

    PlSqlParser.Table_ref_aux_internal_oneContext internalOne =
        (PlSqlParser.Table_ref_aux_internal_oneContext) internal;

    PlSqlParser.Dml_table_expression_clauseContext dmlTable =
        internalOne.dml_table_expression_clause();
    if (dmlTable == null) {
      throw new TransformationException("Invalid table reference structure");
    }

    // Get table name
    PlSqlParser.Tableview_nameContext tableviewName = dmlTable.tableview_name();
    if (tableviewName == null) {
      throw new TransformationException("Cannot extract table name from FROM clause");
    }

    String tableName = tableviewName.getText();

    // Get table alias (if present)
    String tableAlias = null;
    PlSqlParser.Table_aliasContext aliasCtx = tableRefAux.table_alias();
    if (aliasCtx != null) {
      tableAlias = aliasCtx.getText();
    }

    return new TableInfo(tableName, tableAlias);
  }

  /**
   * Scans query block for pseudo-column usage.
   */
  private static PseudoColumnUsage scanPseudoColumnUsage(PlSqlParser.Query_blockContext ctx) {
    PseudoColumnScanner scanner = new PseudoColumnScanner();

    // Scan SELECT list
    PlSqlParser.Selected_listContext selectedList = ctx.selected_list();
    if (selectedList != null) {
      scanner.scanForPseudoColumns(selectedList, true);  // isSelectList=true
    }

    // Scan WHERE clause
    PlSqlParser.Where_clauseContext whereClause = ctx.where_clause();
    if (whereClause != null) {
      scanner.scanForPseudoColumns(whereClause, false);  // isSelectList=false
    }

    return scanner.getUsage();
  }

  /**
   * Detects and extracts SYS_CONNECT_BY_PATH function calls.
   *
   * <p>Scans SELECT list, WHERE clause, and ORDER BY for SYS_CONNECT_BY_PATH calls.
   * Creates unique PathColumnInfo for each unique (expression, separator) pair.</p>
   *
   * <p>Example:
   * <pre>
   * SELECT SYS_CONNECT_BY_PATH(emp_name, '/') as path1,
   *        SYS_CONNECT_BY_PATH(dept, '>') as path2
   * </pre>
   * Generates 2 PathColumnInfo objects with column names "path_1" and "path_2".</p>
   *
   * @param ctx Query block context
   * @return List of PathColumnInfo (empty if no SYS_CONNECT_BY_PATH found)
   */
  private static List<PathColumnInfo> detectPathColumns(PlSqlParser.Query_blockContext ctx) {
    PathColumnDetector detector = new PathColumnDetector();

    // Scan SELECT list
    PlSqlParser.Selected_listContext selectedList = ctx.selected_list();
    if (selectedList != null) {
      detector.scan(selectedList);
    }

    // Scan WHERE clause
    PlSqlParser.Where_clauseContext whereClause = ctx.where_clause();
    if (whereClause != null) {
      detector.scan(whereClause);
    }

    // Scan ORDER BY
    PlSqlParser.Order_by_clauseContext orderBy = ctx.order_by_clause();
    if (orderBy != null) {
      detector.scan(orderBy);
    }

    return detector.getPathColumns();
  }

  /**
   * Simple data holder for table information.
   */
  private static class TableInfo {
    final String tableName;
    final String tableAlias;  // May be null

    TableInfo(String tableName, String tableAlias) {
      this.tableName = tableName;
      this.tableAlias = tableAlias;
    }
  }

  /**
   * Scans AST for pseudo-column usage (LEVEL, CONNECT_BY_ROOT, etc.).
   */
  private static class PseudoColumnScanner {
    private boolean usesLevelInSelect = false;
    private boolean usesLevelInWhere = false;
    private final Set<String> levelReferencePaths = new HashSet<>();
    private boolean usesConnectByRoot = false;
    private boolean usesConnectByPath = false;
    private boolean usesConnectByIsLeaf = false;

    public PseudoColumnUsage getUsage() {
      return new PseudoColumnUsage(
          usesLevelInSelect,
          usesLevelInWhere,
          levelReferencePaths,
          usesConnectByRoot,
          usesConnectByPath,
          usesConnectByIsLeaf
      );
    }

    public void scanForPseudoColumns(ParseTree tree, boolean isSelectList) {
      if (tree == null) {
        return;
      }

      // Check for LEVEL identifier
      if (tree instanceof PlSqlParser.General_elementContext) {
        if (isLevelIdentifier((PlSqlParser.General_elementContext) tree)) {
          if (isSelectList) {
            usesLevelInSelect = true;
          } else {
            usesLevelInWhere = true;
          }
          levelReferencePaths.add(tree.getText());
        }
      }

      // Check for CONNECT_BY_ROOT
      if (tree instanceof PlSqlParser.Unary_expressionContext) {
        PlSqlParser.Unary_expressionContext unaryCtx = (PlSqlParser.Unary_expressionContext) tree;
        if (unaryCtx.CONNECT_BY_ROOT() != null) {
          usesConnectByRoot = true;
        }
      }

      // Check for SYS_CONNECT_BY_PATH (function call)
      // Functions appear as general_element nodes
      if (tree instanceof PlSqlParser.General_elementContext) {
        String text = tree.getText().toUpperCase();
        if (text.contains("SYS_CONNECT_BY_PATH")) {
          usesConnectByPath = true;
        }
      }

      // Check for CONNECT_BY_ISLEAF (identifier)
      if (tree instanceof PlSqlParser.General_elementContext) {
        if (isConnectByIsLeafIdentifier((PlSqlParser.General_elementContext) tree)) {
          usesConnectByIsLeaf = true;
        }
      }

      // Recursively scan children
      for (int i = 0; i < tree.getChildCount(); i++) {
        scanForPseudoColumns(tree.getChild(i), isSelectList);
      }
    }

    private boolean isLevelIdentifier(PlSqlParser.General_elementContext ctx) {
      List<PlSqlParser.General_element_partContext> parts = ctx.general_element_part();
      if (parts == null || parts.size() != 1) {
        return false;
      }

      PlSqlParser.Id_expressionContext idExpr = parts.get(0).id_expression();
      if (idExpr == null) {
        return false;
      }

      return idExpr.getText().toUpperCase().equals("LEVEL");
    }

    private boolean isConnectByIsLeafIdentifier(PlSqlParser.General_elementContext ctx) {
      List<PlSqlParser.General_element_partContext> parts = ctx.general_element_part();
      if (parts == null || parts.size() != 1) {
        return false;
      }

      PlSqlParser.Id_expressionContext idExpr = parts.get(0).id_expression();
      if (idExpr == null) {
        return false;
      }

      return idExpr.getText().toUpperCase().equals("CONNECT_BY_ISLEAF");
    }
  }

  /**
   * Data holder for pseudo-column usage.
   */
  private static class PseudoColumnUsage {
    final boolean usesLevelInSelect;
    final boolean usesLevelInWhere;
    final Set<String> levelReferencePaths;
    final boolean usesConnectByRoot;
    final boolean usesConnectByPath;
    final boolean usesConnectByIsLeaf;

    PseudoColumnUsage(
        boolean usesLevelInSelect,
        boolean usesLevelInWhere,
        Set<String> levelReferencePaths,
        boolean usesConnectByRoot,
        boolean usesConnectByPath,
        boolean usesConnectByIsLeaf) {
      this.usesLevelInSelect = usesLevelInSelect;
      this.usesLevelInWhere = usesLevelInWhere;
      this.levelReferencePaths = levelReferencePaths;
      this.usesConnectByRoot = usesConnectByRoot;
      this.usesConnectByPath = usesConnectByPath;
      this.usesConnectByIsLeaf = usesConnectByIsLeaf;
    }
  }

  /**
   * Scans AST for SYS_CONNECT_BY_PATH function calls and extracts column info.
   *
   * <p>Uses the proven pattern from DateFunctionTransformer - scanning for
   * general_element_part contexts and extracting function arguments.</p>
   */
  private static class PathColumnDetector {
    // Deduplication map: key = (expressionText + separator), value = PathColumnInfo
    private final Map<String, PathColumnInfo> pathColumnMap = new HashMap<>();
    private int pathColumnCounter = 1;  // For generating unique column names

    public List<PathColumnInfo> getPathColumns() {
      return new ArrayList<>(pathColumnMap.values());
    }

    public void scan(ParseTree tree) {
      if (tree == null) {
        return;
      }

      // Look for general_element_part nodes (where function calls appear)
      if (tree instanceof PlSqlParser.General_element_partContext) {
        PlSqlParser.General_element_partContext partCtx =
            (PlSqlParser.General_element_partContext) tree;
        checkForSysConnectByPath(partCtx);
      }

      // Recursively scan children
      for (int i = 0; i < tree.getChildCount(); i++) {
        scan(tree.getChild(i));
      }
    }

    /**
     * Checks if this general_element_part is a SYS_CONNECT_BY_PATH call.
     */
    private void checkForSysConnectByPath(PlSqlParser.General_element_partContext partCtx) {
      // Get function name from id_expression
      PlSqlParser.Id_expressionContext idExpr = partCtx.id_expression();
      if (idExpr == null) {
        return;  // Not a function call
      }

      String functionName = idExpr.getText();
      if (!functionName.toUpperCase().equals("SYS_CONNECT_BY_PATH")) {
        return;  // Different function
      }

      // Extract function arguments using proven pattern
      List<PlSqlParser.ArgumentContext> args = extractFunctionArguments(partCtx);
      if (args.size() != 2) {
        throw new TransformationException(
            "SYS_CONNECT_BY_PATH requires exactly 2 arguments (column, separator), found: " +
            args.size());
      }

      // First argument: column expression (keep as AST node)
      PlSqlParser.ArgumentContext columnArg = args.get(0);
      PlSqlParser.ExpressionContext columnExpr = columnArg.expression();
      if (columnExpr == null) {
        throw new TransformationException(
            "SYS_CONNECT_BY_PATH first argument must be an expression");
      }

      // Second argument: separator string literal
      PlSqlParser.ArgumentContext separatorArg = args.get(1);
      PlSqlParser.ExpressionContext separatorExpr = separatorArg.expression();
      if (separatorExpr == null) {
        throw new TransformationException(
            "SYS_CONNECT_BY_PATH second argument must be an expression");
      }

      // Extract separator string literal
      String separator = extractStringLiteral(separatorExpr);
      if (separator == null) {
        throw new TransformationException(
            "SYS_CONNECT_BY_PATH separator must be a string literal. Found: " +
            separatorExpr.getText());
      }

      // Create PathColumnInfo with deduplication
      String expressionText = columnExpr.getText();
      String deduplicationKey = expressionText + "|" + separator;

      if (!pathColumnMap.containsKey(deduplicationKey)) {
        String generatedColumnName = "path_" + pathColumnCounter++;
        PathColumnInfo pathInfo = new PathColumnInfo(
            columnExpr,
            separator,
            generatedColumnName,
            expressionText
        );
        pathColumnMap.put(deduplicationKey, pathInfo);
      }
    }

    /**
     * Extracts function arguments from general_element_part.
     * Pattern copied from DateFunctionTransformer.
     */
    private List<PlSqlParser.ArgumentContext> extractFunctionArguments(
        PlSqlParser.General_element_partContext partCtx) {

      List<PlSqlParser.Function_argumentContext> funcArgCtxList = partCtx.function_argument();
      if (funcArgCtxList == null || funcArgCtxList.isEmpty()) {
        return new ArrayList<>();
      }

      PlSqlParser.Function_argumentContext funcArgCtx = funcArgCtxList.get(0);
      List<PlSqlParser.ArgumentContext> arguments = funcArgCtx.argument();
      if (arguments == null) {
        return new ArrayList<>();
      }

      return arguments;
    }

    /**
     * Extracts string literal value from expression.
     * Returns the literal with quotes (e.g., "'/'").
     */
    private String extractStringLiteral(PlSqlParser.ExpressionContext expr) {
      if (expr == null) {
        return null;
      }

      // Try direct text match for simple literals
      String text = expr.getText();
      if (text.startsWith("'") && text.endsWith("'")) {
        return text;
      }

      // Search for quoted_string in tree
      PlSqlParser.Quoted_stringContext quotedString = findQuotedString(expr);
      if (quotedString != null) {
        return quotedString.getText();
      }

      return null;
    }

    /**
     * Recursively searches for quoted_string context.
     */
    private PlSqlParser.Quoted_stringContext findQuotedString(ParseTree tree) {
      if (tree instanceof PlSqlParser.Quoted_stringContext) {
        return (PlSqlParser.Quoted_stringContext) tree;
      }

      for (int i = 0; i < tree.getChildCount(); i++) {
        PlSqlParser.Quoted_stringContext result = findQuotedString(tree.getChild(i));
        if (result != null) {
          return result;
        }
      }

      return null;
    }
  }
}
