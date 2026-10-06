package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitAtom {
  public static String v(PlSqlParser.AtomContext ctx, PostgresCodeBuilder b) {

    // Check for bind_variable (e.g., :variable_name)
    // Transform by stripping : prefix and converting to simple variable reference
    if (ctx.bind_variable() != null) {
      return b.visit(ctx.bind_variable());
    }

    // Check for constant (literals)
    if (ctx.constant() != null) {
      return VisitConstant.v(ctx.constant(), b);
    }

    // Check for inquiry_directive
    if (ctx.inquiry_directive() != null) {
      throw new TransformationException(
          "Inquiry directives not yet supported in minimal implementation");
    }

    // Check for parenthesized subquery
    // Grammar: '(' subquery ')' subquery_operation_part*
    // Oracle and PostgreSQL have identical syntax for parenthesized subqueries
    // Used in SELECT list (scalar subqueries) and WHERE clause comparisons
    if (ctx.subquery() != null) {
      // Recursively transform the subquery (applies all transformations: schema qualification, etc.)
      String subquerySQL = b.visit(ctx.subquery());
      return "(" + subquerySQL + ")";
    }

    // Check for parenthesized expressions
    if (ctx.expressions_() != null) {
      PlSqlParser.Expressions_Context exprsCtx = ctx.expressions_();
      if (exprsCtx.expression() != null && !exprsCtx.expression().isEmpty()) {
        // Single parenthesized expression
        String expr = b.visit(exprsCtx.expression(0));
        return "(" + expr + ")";
      }
      throw new TransformationException(
          "Multi-value parenthesized expressions not yet supported");
    }

    // Visit general_element (the simple case - this is our target!)
    PlSqlParser.General_elementContext generalElemCtx = ctx.general_element();
    if (generalElemCtx == null) {
      throw new TransformationException("Atom missing general_element");
    }

    String result = b.visit(generalElemCtx);

    // Check for outer_join_sign (+) AFTER visiting the general_element
    // Oracle: column_name(+) in WHERE clause
    // PostgreSQL: Handled by OuterJoinAnalyzer, converted to ANSI JOIN syntax
    // The (+) is stripped by the visitor (returns empty string), so we just ignore it here
    if (ctx.outer_join_sign() != null) {
      // The outer_join_sign visitor returns "", so (+) is effectively removed
      // We already have the column name from general_element, so just return that
      b.visit(ctx.outer_join_sign());  // Visit but ignore result (returns "")
    }

    return result;
  }
}
