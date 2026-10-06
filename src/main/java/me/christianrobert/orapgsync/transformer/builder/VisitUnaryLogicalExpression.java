package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitUnaryLogicalExpression {
  public static String v(
      PlSqlParser.Unary_logical_expressionContext ctx, PostgresCodeBuilder b) {

    // Grammar: NOT? multiset_expression unary_logical_operation?

    // Visit multiset_expression (the core expression)
    PlSqlParser.Multiset_expressionContext multisetCtx = ctx.multiset_expression();
    if (multisetCtx == null) {
      throw new TransformationException("Unary logical expression missing multiset_expression");
    }
    String expr = b.visit(multisetCtx);

    // Check for NOT prefix
    if (ctx.NOT() != null) {
      expr = "NOT " + expr;
    }

    // Check for unary_logical_operation suffix (IS NULL, IS NOT NULL, etc.)
    if (ctx.unary_logical_operation() != null) {
      PlSqlParser.Unary_logical_operationContext unaryOp = ctx.unary_logical_operation();
      String operation = visitUnaryLogicalOperation(unaryOp);
      expr = expr + " " + operation;
    }

    return expr;
  }

  private static String visitUnaryLogicalOperation(PlSqlParser.Unary_logical_operationContext ctx) {
    // Grammar: IS NOT? logical_operation
    StringBuilder result = new StringBuilder("IS ");

    if (ctx.NOT() != null) {
      result.append("NOT ");
    }

    PlSqlParser.Logical_operationContext logicalOp = ctx.logical_operation();
    if (logicalOp.NULL_() != null) {
      result.append("NULL");
    } else if (logicalOp.NAN_() != null) {
      // Oracle IS NAN → PostgreSQL IS NOT A NUMBER (not standard, may need custom handling)
      result.append("NAN");
    } else if (logicalOp.INFINITE() != null) {
      // Oracle IS INFINITE (rare)
      result.append("INFINITE");
    } else if (logicalOp.PRESENT() != null) {
      // Oracle IS PRESENT (JSON path expressions)
      throw new TransformationException("IS PRESENT not yet supported");
    } else {
      throw new TransformationException(
          "Unsupported logical operation: " + logicalOp.getText());
    }

    return result.toString();
  }
}
