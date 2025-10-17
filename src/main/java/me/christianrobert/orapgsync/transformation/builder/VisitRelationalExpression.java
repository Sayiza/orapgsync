package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.RelationalExpression;

public class VisitRelationalExpression {
  public static SemanticNode v(
      PlSqlParser.Relational_expressionContext ctx, SemanticTreeBuilder b) {

    // Check for relational operator (=, !=, <, >, <=, >=)
    if (ctx.relational_operator() != null) {
      throw new TransformationException(
          "Relational operators (=, !=, <, >, <=, >=) not yet supported in minimal implementation");
    }

    // Visit compound_expression (the simple case)
    PlSqlParser.Compound_expressionContext compoundCtx = ctx.compound_expression();
    if (compoundCtx == null) {
      throw new TransformationException("Relational expression missing compound_expression");
    }

    SemanticNode compoundExpression = b.visit(compoundCtx);
    return new RelationalExpression(compoundExpression);
  }
}
