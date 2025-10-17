package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitLogicalExpression {
  public static String v(PlSqlParser.Logical_expressionContext ctx, PostgresCodeBuilder b) {

    // Check for AND operation
    if (ctx.AND() != null) {
      throw new TransformationException("Logical AND not yet supported in minimal implementation");
    }

    // Check for OR operation
    if (ctx.OR() != null) {
      throw new TransformationException("Logical OR not yet supported in minimal implementation");
    }

    // Visit unary_logical_expression (the simple case)
    PlSqlParser.Unary_logical_expressionContext unaryCtx = ctx.unary_logical_expression();
    if (unaryCtx == null) {
      throw new TransformationException("Logical expression missing unary_logical_expression");
    }

    return b.visit(unaryCtx);
  }
}
