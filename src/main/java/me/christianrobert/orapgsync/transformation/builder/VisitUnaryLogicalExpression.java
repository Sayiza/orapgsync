package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;
import me.christianrobert.orapgsync.transformation.semantic.expression.UnaryLogicalExpression;

public class VisitUnaryLogicalExpression {
  public static SemanticNode v(
      PlSqlParser.Unary_logical_expressionContext ctx, SemanticTreeBuilder b) {

    // Check for NOT prefix
    if (ctx.NOT() != null) {
      throw new TransformationException("NOT operator not yet supported in minimal implementation");
    }

    // Check for unary_logical_operation suffix (IS NULL, IS NOT NULL, etc.)
    if (ctx.unary_logical_operation() != null) {
      throw new TransformationException(
          "Unary logical operations (IS NULL, IS NOT NULL) not yet supported in minimal implementation");
    }

    // Visit multiset_expression (the common case)
    PlSqlParser.Multiset_expressionContext multisetCtx = ctx.multiset_expression();
    if (multisetCtx == null) {
      throw new TransformationException("Unary logical expression missing multiset_expression");
    }

    SemanticNode multisetExpression = b.visit(multisetCtx);
    return new UnaryLogicalExpression(multisetExpression);
  }
}
