package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting conditions (WHERE clause conditions).
 */
public class VisitCondition {
  public static String v(PlSqlParser.ConditionContext ctx, PostgresCodeBuilder b) {
    // Grammar: expression | JSON_EQUAL '(' expressions_ ')'

    if (ctx.JSON_EQUAL() != null) {
      throw new TransformationException("JSON_EQUAL conditions not yet supported");
    }

    PlSqlParser.ExpressionContext exprCtx = ctx.expression();
    if (exprCtx == null) {
      throw new TransformationException("Condition missing expression");
    }

    return b.visit(exprCtx);
  }
}
