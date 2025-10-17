package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.ModelExpression;

public class VisitModelExpression {
  public static SemanticNode v(PlSqlParser.Model_expressionContext ctx, SemanticTreeBuilder b) {

    // Check for model clause array indexing [...]
    if (ctx.model_expression_element() != null) {
      throw new TransformationException(
          "Model clause array indexing not yet supported in minimal implementation");
    }

    // Visit unary_expression (the simple case)
    PlSqlParser.Unary_expressionContext unaryCtx = ctx.unary_expression();
    if (unaryCtx == null) {
      throw new TransformationException("Model expression missing unary_expression");
    }

    SemanticNode unaryExpression = b.visit(unaryCtx);
    return new ModelExpression(unaryExpression);
  }
}
