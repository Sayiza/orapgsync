package me.christianrobert.orapgsync.transformer.builder.connectby;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.HashSet;
import java.util.List;
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
      if (tree instanceof PlSqlParser.Function_callContext) {
        PlSqlParser.Function_callContext funcCtx = (PlSqlParser.Function_callContext) tree;
        String funcName = funcCtx.getText().toUpperCase();
        if (funcName.startsWith("SYS_CONNECT_BY_PATH")) {
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
}
