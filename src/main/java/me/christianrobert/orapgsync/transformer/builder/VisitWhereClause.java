package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting WHERE clauses.
 */
public class VisitWhereClause {
  public static String v(PlSqlParser.Where_clauseContext ctx, PostgresCodeBuilder b) {
    // Grammar: WHERE (CURRENT OF cursor_name | condition)

    if (ctx.CURRENT() != null) {
      throw new TransformationException("CURRENT OF cursor not yet supported");
    }

    PlSqlParser.ConditionContext conditionCtx = ctx.condition();
    if (conditionCtx == null) {
      throw new TransformationException("WHERE clause missing condition");
    }

    String condition = b.visit(conditionCtx);
    return "WHERE " + condition;
  }
}
