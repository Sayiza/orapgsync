package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitSelectOnlyStatement {
  public static String v(PlSqlParser.Select_only_statementContext ctx, PostgresCodeBuilder b) {
    // Grammar: with_clause? subquery
    StringBuilder result = new StringBuilder();

    // Handle WITH clause if present
    PlSqlParser.With_clauseContext withClause = ctx.with_clause();
    if (withClause != null) {
      result.append(VisitWithClause.v(withClause, b));
      result.append(" ");
    }

    // Visit subquery
    PlSqlParser.SubqueryContext subqueryCtx = ctx.subquery();
    if (subqueryCtx == null) {
      throw new TransformationException("SELECT_ONLY statement missing subquery");
    }

    result.append(b.visit(subqueryCtx));

    return result.toString();
  }
}
