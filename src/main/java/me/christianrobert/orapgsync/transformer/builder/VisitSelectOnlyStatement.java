package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitSelectOnlyStatement {
  public static String v(PlSqlParser.Select_only_statementContext ctx, PostgresCodeBuilder b) {
    // subquery for_update_clause?

    // Note: FOR UPDATE detection would go here when implementing
    // Current grammar doesn't expose for_update_clause() method in this context

    // Visit subquery
    PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();
    if (subqueryCtx == null) {
      throw new TransformationException("SELECT_ONLY statement missing subquery");
    }

    return b.visit(subqueryCtx);
  }
}
