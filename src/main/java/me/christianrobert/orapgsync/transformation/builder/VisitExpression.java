package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.Expression;

public class VisitExpression {
  public static SemanticNode v(PlSqlParser.ExpressionContext ctx, SemanticTreeBuilder b) {

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

    SemanticNode logicalExpression = b.visit(logicalExprCtx);
    return new Expression(logicalExpression);
  }
}
