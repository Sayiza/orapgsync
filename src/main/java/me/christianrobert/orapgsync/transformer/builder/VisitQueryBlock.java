package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitQueryBlock {
  public static String v(PlSqlParser.Query_blockContext ctx, PostgresCodeBuilder b) {

    // Extract SELECT list - use visitor pattern
    PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
    if (selectedListCtx == null) {
      throw new TransformationException("Query block missing selected_list");
    }
    String selectedList = b.visit(selectedListCtx);

    // Extract FROM clause - use visitor pattern
    PlSqlParser.From_clauseContext fromClauseCtx = ctx.from_clause();
    if (fromClauseCtx == null) {
      throw new TransformationException("Query block missing from_clause (FROM DUAL not yet supported in minimal implementation)");
    }
    String fromClause = b.visit(fromClauseCtx);

    // Build the SELECT statement
    StringBuilder result = new StringBuilder();
    result.append("SELECT ").append(selectedList).append(" FROM ").append(fromClause);

    // Extract WHERE clause (if present)
    PlSqlParser.Where_clauseContext whereCtx = ctx.where_clause();
    if (whereCtx != null) {
      String whereClause = b.visit(whereCtx);
      result.append(" ").append(whereClause);
    }

    return result.toString();
  }
}
