package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitModelExpression {
  public static String v(PlSqlParser.Model_expressionContext ctx, PostgresCodeBuilder b) {

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

    return b.visit(unaryCtx);
  }
}
