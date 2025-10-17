package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;

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

    return "SELECT " + selectedList + " FROM " + fromClause;
  }
}
