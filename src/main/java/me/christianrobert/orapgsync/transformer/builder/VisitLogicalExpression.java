package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitLogicalExpression {
  public static String v(PlSqlParser.Logical_expressionContext ctx, PostgresCodeBuilder b) {

    // Check for AND operation
    if (ctx.AND() != null) {
      // Grammar: logical_expression AND logical_expression
      java.util.List<PlSqlParser.Logical_expressionContext> logicalExprs = ctx.logical_expression();
      if (logicalExprs == null || logicalExprs.size() != 2) {
        throw new TransformationException("Logical AND expression must have exactly 2 operands");
      }
      String left = b.visit(logicalExprs.get(0));
      String right = b.visit(logicalExprs.get(1));
      return left + " AND " + right;
    }

    // Check for OR operation
    if (ctx.OR() != null) {
      // Grammar: logical_expression OR logical_expression
      java.util.List<PlSqlParser.Logical_expressionContext> logicalExprs = ctx.logical_expression();
      if (logicalExprs == null || logicalExprs.size() != 2) {
        throw new TransformationException("Logical OR expression must have exactly 2 operands");
      }
      String left = b.visit(logicalExprs.get(0));
      String right = b.visit(logicalExprs.get(1));
      return left + " OR " + right;
    }

    // Visit unary_logical_expression (the simple case)
    PlSqlParser.Unary_logical_expressionContext unaryCtx = ctx.unary_logical_expression();
    if (unaryCtx == null) {
      throw new TransformationException("Logical expression missing unary_logical_expression");
    }

    return b.visit(unaryCtx);
  }
}
