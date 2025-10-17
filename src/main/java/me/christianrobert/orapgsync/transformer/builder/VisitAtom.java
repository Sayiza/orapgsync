package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformation.context.TransformationException;

public class VisitAtom {
  public static String v(PlSqlParser.AtomContext ctx, PostgresCodeBuilder b) {

    // Check for bind_variable
    if (ctx.bind_variable() != null) {
      throw new TransformationException(
          "Bind variables not yet supported in minimal implementation");
    }

    // Check for constant (literals)
    if (ctx.constant() != null) {
      throw new TransformationException(
          "Literal constants not yet supported in minimal implementation");
    }

    // Check for inquiry_directive
    if (ctx.inquiry_directive() != null) {
      throw new TransformationException(
          "Inquiry directives not yet supported in minimal implementation");
    }

    // Check for parenthesized subquery
    if (ctx.subquery() != null) {
      throw new TransformationException(
          "Parenthesized subqueries not yet supported in minimal implementation");
    }

    // Check for parenthesized expressions
    if (ctx.expressions_() != null) {
      throw new TransformationException(
          "Parenthesized expressions not yet supported in minimal implementation");
    }

    // Check for outer_join_sign (+)
    if (ctx.outer_join_sign() != null) {
      throw new TransformationException(
          "Outer join sign (+) not yet supported in minimal implementation");
    }

    // Visit general_element (the simple case - this is our target!)
    PlSqlParser.General_elementContext generalElemCtx = ctx.general_element();
    if (generalElemCtx == null) {
      throw new TransformationException("Atom missing general_element");
    }

    return b.visit(generalElemCtx);
  }
}
