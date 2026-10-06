package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import static me.christianrobert.orapgsync.transformer.builder.functions.DateArithmeticTransformer.isDateArithmetic;
import static me.christianrobert.orapgsync.transformer.builder.functions.DateArithmeticTransformer.transformDateArithmetic;

public class VisitConcatenation {
  public static String v(PlSqlParser.ConcatenationContext ctx, PostgresCodeBuilder b) {

    // Grammar: concatenation op concatenation (left-recursive)
    // or: model_expression (AT ... | interval_expression)? (ON OVERFLOW ...)?

    // Check for binary operators (left-recursive rules)
    if (ctx.DOUBLE_ASTERISK() != null) {
      // ** power operator
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return left + " ^ " + right;  // PostgreSQL uses ^ for power
    }

    if (ctx.ASTERISK() != null) {
      // * multiplication
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return left + " * " + right;
    }

    if (ctx.SOLIDUS() != null) {
      // / division
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return left + " / " + right;
    }

    if (ctx.MOD() != null) {
      // MOD operator
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return "MOD(" + left + ", " + right + ")";  // PostgreSQL MOD function
    }

    if (ctx.PLUS_SIGN() != null) {
      // + addition
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();

      // Check if this is date arithmetic (date + integer or integer + date)
      // Uses heuristic detection now, will be replaced with type inference in Phase 2
      // See DateArithmeticTransformer for detailed documentation
      if (isDateArithmetic(operands.get(0), operands.get(1), "+", b)) {
        String left = b.visit(operands.get(0));
        String right = b.visit(operands.get(1));
        return transformDateArithmetic(left, right, operands.get(0), operands.get(1), "+", b);
      }

      // Regular numeric addition
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return left + " + " + right;
    }

    if (ctx.MINUS_SIGN() != null) {
      // - subtraction
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();

      // Check if this is date arithmetic (date - integer)
      // Note: date1 - date2 returns interval in both Oracle and PostgreSQL, no transformation needed
      // Uses heuristic detection now, will be replaced with type inference in Phase 2
      // See DateArithmeticTransformer for detailed documentation
      if (isDateArithmetic(operands.get(0), operands.get(1), "-", b)) {
        String left = b.visit(operands.get(0));
        String right = b.visit(operands.get(1));
        return transformDateArithmetic(left, right, operands.get(0), operands.get(1), "-", b);
      }

      // Regular numeric subtraction or date1 - date2
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return left + " - " + right;
    }

    // Check for || concatenation operator (BAR BAR)
    if (ctx.BAR() != null && ctx.BAR().size() >= 2) {
      // String concatenation - CRITICAL NULL HANDLING DIFFERENCE:
      // Oracle: NULL is treated as empty string ('Hello' || NULL || 'World' = 'HelloWorld')
      // PostgreSQL ||: NULL propagates ('Hello' || NULL || 'World' = NULL)
      // PostgreSQL CONCAT(): NULL treated as empty string (matches Oracle behavior)
      // Solution: Transform || to CONCAT() for correct Oracle semantics
      java.util.List<PlSqlParser.ConcatenationContext> operands = ctx.concatenation();
      String left = b.visit(operands.get(0));
      String right = b.visit(operands.get(1));
      return "CONCAT( " + left + " , " + right + " )";
    }

    if (ctx.COLLATE() != null) {
      // COLLATE clause
      throw new TransformationException("COLLATE not yet supported");
    }

    // Check for AT LOCAL/TIME ZONE
    if (ctx.AT() != null) {
      throw new TransformationException(
          "AT LOCAL/TIME ZONE not yet supported in minimal implementation");
    }

    // Check for interval_expression
    if (ctx.interval_expression() != null) {
      throw new TransformationException(
          "Interval expressions not yet supported in minimal implementation");
    }

    // Visit model_expression (the simple case)
    PlSqlParser.Model_expressionContext modelCtx = ctx.model_expression();
    if (modelCtx == null) {
      throw new TransformationException("Concatenation missing model_expression");
    }

    return b.visit(modelCtx);
  }
}
