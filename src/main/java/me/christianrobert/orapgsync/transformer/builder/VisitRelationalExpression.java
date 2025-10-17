package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitRelationalExpression {
  public static String v(
      PlSqlParser.Relational_expressionContext ctx, PostgresCodeBuilder b) {

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

    return b.visit(compoundCtx);
  }
}
