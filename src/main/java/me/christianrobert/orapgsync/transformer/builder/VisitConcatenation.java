package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.Concatenation;

public class VisitConcatenation {
  public static String v(PlSqlParser.ConcatenationContext ctx, PostgresCodeBuilder b) {

    // Check for || concatenation operator (BAR BAR)
    if (ctx.BAR() != null && ctx.BAR().size() >= 2) {
      throw new TransformationException(
          "String concatenation operator || not yet supported in minimal implementation");
    }

    // Check for AT LOCAL/TIME ZONE
    if (ctx.AT() != null) {
      throw new TransformationException(
          "AT LOCAL/TIME ZONE not yet supported in minimal implementation");
    }

    // Check for interval_expression
    if (ctx.interval_expression() != null) {
      throw new TransformationException(
          "Interval expressions not yet supported in minimal implementation");
    }

    // Visit model_expression (the simple case)
    PlSqlParser.Model_expressionContext modelCtx = ctx.model_expression();
    if (modelCtx == null) {
      throw new TransformationException("Concatenation missing model_expression");
    }

    return b.visit(modelCtx);
  }
}
