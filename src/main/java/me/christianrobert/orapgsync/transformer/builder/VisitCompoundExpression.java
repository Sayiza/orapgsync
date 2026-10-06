package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

public class VisitCompoundExpression {
  public static String v(
      PlSqlParser.Compound_expressionContext ctx, PostgresCodeBuilder b) {

    // Grammar: concatenation (NOT? (IN in_elements | BETWEEN between_elements | LIKE...))?

    // Visit the left-hand concatenation (always present)
    PlSqlParser.ConcatenationContext leftConcat = ctx.concatenation(0);
    if (leftConcat == null) {
      throw new TransformationException("Compound expression missing concatenation");
    }
    String left = b.visit(leftConcat);

    // Check for NOT modifier
    boolean hasNot = ctx.NOT() != null;

    // Check for IN operations
    if (ctx.IN() != null) {
      String inElements = visitInElements(ctx.in_elements(), b);
      String operator = hasNot ? " NOT IN " : " IN ";
      return left + operator + inElements;
    }

    // Check for BETWEEN operations
    if (ctx.BETWEEN() != null) {
      String betweenElements = visitBetweenElements(ctx.between_elements(), b);
      String operator = hasNot ? " NOT BETWEEN " : " BETWEEN ";
      return left + operator + betweenElements;
    }

    // Check for LIKE operations
    if (ctx.LIKE() != null || ctx.LIKEC() != null || ctx.LIKE2() != null || ctx.LIKE4() != null) {
      // Oracle has LIKE, LIKEC (C semantics), LIKE2 (UCS2), LIKE4 (UCS4)
      // PostgreSQL only has LIKE (treat all variants as LIKE)
      java.util.List<PlSqlParser.ConcatenationContext> concats = ctx.concatenation();
      if (concats.size() < 2) {
        throw new TransformationException("LIKE operation missing pattern");
      }
      String pattern = b.visit(concats.get(1));
      String operator = hasNot ? " NOT LIKE " : " LIKE ";
      String result = left + operator + pattern;

      // Handle ESCAPE clause if present
      if (concats.size() >= 3 && ctx.ESCAPE() != null) {
        String escapeChar = b.visit(concats.get(2));
        result += " ESCAPE " + escapeChar;
      }

      return result;
    }

    // No compound operation, just return the left concatenation
    return left;
  }

  private static String visitInElements(PlSqlParser.In_elementsContext ctx, PostgresCodeBuilder b) {
    // Grammar: '(' subquery ')' | '(' concatenation (',' concatenation)* ')' | constant | bind_variable

    if (ctx.subquery() != null) {
      // IN (subquery)
      String subquery = b.visit(ctx.subquery());
      return "( " + subquery + " )";
    }

    if (ctx.concatenation() != null && !ctx.concatenation().isEmpty()) {
      // IN (value1, value2, ...)
      java.util.List<String> values = new java.util.ArrayList<>();
      for (PlSqlParser.ConcatenationContext concat : ctx.concatenation()) {
        values.add(b.visit(concat));
      }
      return "( " + String.join(", ", values) + " )";
    }

    if (ctx.constant() != null) {
      // IN constant (rare, but valid)
      return b.visit(ctx.constant());
    }

    if (ctx.bind_variable() != null) {
      throw new TransformationException("Bind variables not yet supported");
    }

    throw new TransformationException("Unsupported IN elements: " + ctx.getText());
  }

  private static String visitBetweenElements(
      PlSqlParser.Between_elementsContext ctx, PostgresCodeBuilder b) {
    // Grammar: concatenation AND concatenation
    java.util.List<PlSqlParser.ConcatenationContext> concats = ctx.concatenation();
    if (concats == null || concats.size() != 2) {
      throw new TransformationException("BETWEEN must have exactly 2 operands");
    }

    String lower = b.visit(concats.get(0));
    String upper = b.visit(concats.get(1));

    return lower + " AND " + upper;
  }
}
