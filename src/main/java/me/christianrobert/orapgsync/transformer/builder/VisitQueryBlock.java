package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinAnalyzer;
import me.christianrobert.orapgsync.transformer.builder.outerjoin.OuterJoinContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;

public class VisitQueryBlock {
  public static String v(PlSqlParser.Query_blockContext ctx, PostgresCodeBuilder b) {

    // Get FROM and WHERE clauses
    PlSqlParser.From_clauseContext fromClauseCtx = ctx.from_clause();
    PlSqlParser.Where_clauseContext whereCtx = ctx.where_clause();

    // Check if this is a FROM DUAL query (Oracle-specific pattern for scalar expressions)
    // Oracle: SELECT SYSDATE FROM DUAL → PostgreSQL: SELECT CURRENT_TIMESTAMP
    boolean isDualQuery = (fromClauseCtx != null) && isDualTable(fromClauseCtx);

    // If no FROM clause and not DUAL, this is an error
    if (fromClauseCtx == null && !isDualQuery) {
      throw new TransformationException("Query block missing from_clause");
    }

    // PHASE 1: ANALYSIS - Scan FROM and WHERE to detect outer joins
    // This must happen BEFORE visiting FROM/WHERE to prepare transformation context
    OuterJoinContext outerJoinContext = OuterJoinAnalyzer.analyze(fromClauseCtx, whereCtx);

    // Push the outer join context onto the stack for this query level
    // This handles nested queries (subqueries) - each query gets its own context
    b.pushOuterJoinContext(outerJoinContext);

    try {
      // PHASE 2: TRANSFORMATION - Visit clauses with prepared context

      // IMPORTANT: Visit FROM clause FIRST to register table aliases
      // before processing SELECT list (which may reference those aliases for type methods)
      // The FROM visitor will now use outerJoinContext to generate ANSI JOIN syntax
      // NOTE: If this is a DUAL query, we'll visit but not output the FROM clause
      String fromClause = null;
      if (!isDualQuery && fromClauseCtx != null) {
        fromClause = b.visit(fromClauseCtx);
      }

      // Extract SELECT list - use visitor pattern
      PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
      if (selectedListCtx == null) {
        throw new TransformationException("Query block missing selected_list");
      }
      String selectedList = b.visit(selectedListCtx);

      // Build the SELECT statement
      StringBuilder result = new StringBuilder();
      result.append("SELECT ").append(selectedList);

      // Only add FROM clause if not a DUAL query
      // Oracle: SELECT SYSDATE FROM DUAL → PostgreSQL: SELECT CURRENT_TIMESTAMP
      if (!isDualQuery && fromClause != null) {
        result.append(" FROM ").append(fromClause);
      }

      // Extract WHERE clause (if present)
      // The WHERE visitor will now use outerJoinContext to filter out (+) conditions
      if (whereCtx != null) {
        String whereClause = b.visit(whereCtx);
        if (whereClause != null && !whereClause.trim().isEmpty()) {
          result.append(" ").append(whereClause);
        }
      }

      // Extract GROUP BY clause (if present)
      // Note: Grammar allows multiple group_by_clause in a list, but typically just one
      List<PlSqlParser.Group_by_clauseContext> groupByCtxList = ctx.group_by_clause();
      if (groupByCtxList != null && !groupByCtxList.isEmpty()) {
        for (PlSqlParser.Group_by_clauseContext groupByCtx : groupByCtxList) {
          String groupByClause = b.visit(groupByCtx);
          if (groupByClause != null && !groupByClause.trim().isEmpty()) {
            result.append(" ").append(groupByClause);
          }
        }
      }

      // Extract ORDER BY clause (if present)
      PlSqlParser.Order_by_clauseContext orderByCtx = ctx.order_by_clause();
      if (orderByCtx != null) {
        String orderByClause = b.visit(orderByCtx);
        if (orderByClause != null && !orderByClause.trim().isEmpty()) {
          result.append(" ").append(orderByClause);
        }
      }

      return result.toString();

    } finally {
      // Always pop the context when leaving this query level (even if exception occurs)
      // This ensures nested subqueries don't corrupt parent query contexts
      b.popOuterJoinContext();
    }
  }

  /**
   * Checks if the FROM clause references only the DUAL table.
   *
   * <p>Oracle uses DUAL (a special single-row table) for queries without real tables:
   * <ul>
   *   <li>SELECT SYSDATE FROM DUAL</li>
   *   <li>SELECT 1 + 1 FROM DUAL</li>
   *   <li>SELECT 'Hello World' FROM DUAL</li>
   * </ul>
   *
   * <p>PostgreSQL doesn't need FROM clause for scalar expressions:
   * <ul>
   *   <li>SELECT CURRENT_TIMESTAMP</li>
   *   <li>SELECT 1 + 1</li>
   *   <li>SELECT 'Hello World'</li>
   * </ul>
   *
   * @param fromClauseCtx The FROM clause context
   * @return true if FROM clause contains only DUAL table
   */
  private static boolean isDualTable(PlSqlParser.From_clauseContext fromClauseCtx) {
    if (fromClauseCtx == null) {
      return false;
    }

    PlSqlParser.Table_ref_listContext tableRefListCtx = fromClauseCtx.table_ref_list();
    if (tableRefListCtx == null) {
      return false;
    }

    List<PlSqlParser.Table_refContext> tableRefs = tableRefListCtx.table_ref();
    if (tableRefs == null || tableRefs.size() != 1) {
      // Not DUAL if multiple tables or no tables
      return false;
    }

    // Get the single table reference
    PlSqlParser.Table_refContext tableRef = tableRefs.get(0);
    PlSqlParser.Table_ref_auxContext tableRefAux = tableRef.table_ref_aux();
    if (tableRefAux == null) {
      return false;
    }

    PlSqlParser.Table_ref_aux_internalContext internal = tableRefAux.table_ref_aux_internal();
    if (internal == null) {
      return false;
    }

    // Check if it's a simple table reference (not a subquery)
    if (!(internal instanceof PlSqlParser.Table_ref_aux_internal_oneContext)) {
      return false;
    }

    PlSqlParser.Table_ref_aux_internal_oneContext internalOne =
        (PlSqlParser.Table_ref_aux_internal_oneContext) internal;
    PlSqlParser.Dml_table_expression_clauseContext dmlTable = internalOne.dml_table_expression_clause();
    if (dmlTable == null) {
      return false;
    }

    // Check if it's a tableview_name (not a subquery)
    PlSqlParser.Tableview_nameContext tableviewName = dmlTable.tableview_name();
    if (tableviewName == null) {
      return false;
    }

    // Get the table name (case-insensitive)
    String tableName = tableviewName.getText().toUpperCase();

    // Check if it's DUAL or SYS.DUAL
    return tableName.equals("DUAL") || tableName.equals("SYS.DUAL");
  }
}
