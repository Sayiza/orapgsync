package me.christianrobert.orapgsync.transformation.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.semantic.SemanticNode;
import me.christianrobert.orapgsync.transformation.semantic.expression.Identifier;

public class VisitExpression {
  public static SemanticNode v(PlSqlParser.ExpressionContext ctx, SemanticTreeBuilder b) {

    // In minimal implementation, assume expression is just a simple identifier
    // Future phases will handle complex expressions, function calls, operators, etc.
    String text = ctx.getText();
    return new Identifier(text);
  }
}
