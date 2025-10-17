package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;

public class VisitUnaryExpression {
  public static String v(PlSqlParser.Unary_expressionContext ctx, PostgresCodeBuilder b) {

    // Check for unary operators (+, -)
    if (ctx.getChild(0).getText().equals("+") || ctx.getChild(0).getText().equals("-")) {
      throw new TransformationException(
          "Unary operators (+, -) not yet supported in minimal implementation");
    }

    // Check for PRIOR operator
    if (ctx.PRIOR() != null) {
      throw new TransformationException(
          "PRIOR operator not yet supported in minimal implementation");
    }

    // Check for CONNECT_BY_ROOT operator
    if (ctx.CONNECT_BY_ROOT() != null) {
      throw new TransformationException(
          "CONNECT_BY_ROOT operator not yet supported in minimal implementation");
    }

    // Check for NEW operator
    if (ctx.NEW() != null) {
      throw new TransformationException(
          "NEW operator not yet supported in minimal implementation");
    }

    // Check for DISTINCT operator
    if (ctx.DISTINCT() != null) {
      throw new TransformationException(
          "DISTINCT operator not yet supported in minimal implementation");
    }

    // Check for ALL operator
    if (ctx.ALL() != null) {
      throw new TransformationException(
          "ALL operator not yet supported in minimal implementation");
    }

    // Check for case_expression
    if (ctx.case_expression() != null) {
      throw new TransformationException(
          "CASE expressions not yet supported in minimal implementation");
    }

    // Check for quantified_expression
    if (ctx.quantified_expression() != null) {
      throw new TransformationException(
          "Quantified expressions (SOME, EXISTS, ALL, ANY) not yet supported in minimal implementation");
    }

    // Route to standard_function (NVL, DECODE, SUBSTR, etc.)
    if (ctx.standard_function() != null) {
      return b.visit(ctx.standard_function());
    }

    // Check for implicit_cursor_expression
    if (ctx.implicit_cursor_expression() != null) {
      throw new TransformationException(
          "Implicit cursor expressions (SQL%...) not yet supported in minimal implementation");
    }

    // Check for dot navigation (unary_expression '.' ...)
    // This would show up as having more than just an atom child
    // For now, we'll detect it by checking if there are multiple relational_expressions
    // which would indicate a more complex structure

    // Route to atom (identifiers, literals, subqueries, etc.)
    PlSqlParser.AtomContext atomCtx = ctx.atom();
    if (atomCtx != null) {
      return b.visit(atomCtx);
    }

    throw new TransformationException(
        "Unary expression has no recognized branch (standard_function or atom)");
  }
}
