package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.FromClause;
import me.christianrobert.orapgsync.transformation.semantic.query.QueryBlock;
import me.christianrobert.orapgsync.transformation.semantic.query.SelectedList;

public class VisitQueryBlock {
  public static SemanticNode v(PlSqlParser.Query_blockContext ctx, SemanticTreeBuilder b) {

    // Extract SELECT list - use visitor pattern
    PlSqlParser.Selected_listContext selectedListCtx = ctx.selected_list();
    if (selectedListCtx == null) {
      throw new TransformationException("Query block missing selected_list");
    }
    SelectedList selectedList = (SelectedList) b.visit(selectedListCtx);

    // Extract FROM clause - use visitor pattern
    PlSqlParser.From_clauseContext fromClauseCtx = ctx.from_clause();
    if (fromClauseCtx == null) {
      throw new TransformationException("Query block missing from_clause (FROM DUAL not yet supported in minimal implementation)");
    }
    FromClause fromClause = (FromClause) b.visit(fromClauseCtx);

    return new QueryBlock(selectedList, fromClause);
  }
}
