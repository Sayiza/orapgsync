package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitSelectStatement {
  public static String v(PlSqlParser.Select_statementContext ctx, PostgresCodeBuilder b) {
    PlSqlParser.Select_only_statementContext selectOnlyCtx = ctx.select_only_statement();
    if (selectOnlyCtx == null) {
      throw new TransformationException("SELECT statement missing select_only_statement");
    }

    return b.visit(selectOnlyCtx);
  }
}
