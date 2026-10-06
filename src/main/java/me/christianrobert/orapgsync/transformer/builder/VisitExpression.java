package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitExpression {
  public static String v(PlSqlParser.ExpressionContext ctx, PostgresCodeBuilder b) {

    // Check for cursor_expression
    PlSqlParser.Cursor_expressionContext cursorExprCtx = ctx.cursor_expression();
    if (cursorExprCtx != null) {
      throw new TransformationException("CURSOR expressions not yet supported");
    }

    // Visit logical_expression (the common case)
    PlSqlParser.Logical_expressionContext logicalExprCtx = ctx.logical_expression();
    if (logicalExprCtx == null) {
      throw new TransformationException("Expression missing logical_expression");
    }

    return b.visit(logicalExprCtx);
  }
}
