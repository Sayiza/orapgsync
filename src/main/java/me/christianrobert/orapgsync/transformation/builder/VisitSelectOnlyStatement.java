package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.query.Subquery;
import me.christianrobert.orapgsync.transformation.semantic.statement.SelectOnlyStatement;

public class VisitSelectOnlyStatement {
  public static SemanticNode v(PlSqlParser.Select_only_statementContext ctx, SemanticTreeBuilder b) {
    // subquery for_update_clause?

    // Note: FOR UPDATE detection would go here when implementing
    // Current grammar doesn't expose for_update_clause() method in this context

    // Visit subquery
    PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();
    if (subqueryCtx == null) {
      throw new TransformationException("SELECT_ONLY statement missing subquery");
    }

    Subquery subquery = (Subquery) b.visit(subqueryCtx);
    return new SelectOnlyStatement(subquery);
  }
}
