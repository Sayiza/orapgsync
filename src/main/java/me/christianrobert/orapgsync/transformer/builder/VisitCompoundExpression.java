package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;

public class VisitCompoundExpression {
  public static String v(
      PlSqlParser.Compound_expressionContext ctx, PostgresCodeBuilder b) {

    // Check for IN operations
    if (ctx.IN() != null) {
      throw new TransformationException("IN operations not yet supported in minimal implementation");
    }

    // Check for BETWEEN operations
    if (ctx.BETWEEN() != null) {
      throw new TransformationException(
          "BETWEEN operations not yet supported in minimal implementation");
    }

    // Check for LIKE operations
    if (ctx.LIKE() != null || ctx.LIKEC() != null || ctx.LIKE2() != null || ctx.LIKE4() != null) {
      throw new TransformationException(
          "LIKE operations not yet supported in minimal implementation");
    }

    // Visit concatenation (the simple case)
    PlSqlParser.ConcatenationContext concatenationCtx = ctx.concatenation(0);
    if (concatenationCtx == null) {
      throw new TransformationException("Compound expression missing concatenation");
    }

    return b.visit(concatenationCtx);
  }
}
